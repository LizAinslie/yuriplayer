package capital.yuri.yuriplayer.data.source

import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * Subsonic / OpenSubsonic REST client (JSON responses).
 * Token auth: u + t=md5(password+salt) + s + v + c + f=json
 */
class SubsonicClient(
    private val http: HttpClient,
    private val json: Json
) {
    data class Session(
        val baseUrl: String,
        val username: String,
        val password: String,
        val clientName: String = "YuriPlayer"
    )

    suspend fun ping(session: Session): Result<Unit> = runCatching {
        val body = apiGet(session, "ping")
        val root = json.decodeFromString<SubsonicResponse>(body)
        if (root.subsonicResponse.status != "ok") {
            error(root.subsonicResponse.error?.message ?: "ping failed")
        }
    }.onFailure { Log.w(TAG, "ping failed", it) }

    /**
     * Walks indexes → artists → albums → songs. Fine for moderate libraries;
     * large servers should later switch to getAlbumList2 paging + getAlbum.
     */
    suspend fun listAllSongs(session: Session): Result<List<Song>> = runCatching {
        val songs = mutableListOf<Song>()
        val indexesBody = apiGet(session, "getIndexes")
        val indexes = json.decodeFromString<SubsonicResponse>(indexesBody)
            .subsonicResponse.indexes ?: return@runCatching emptyList()

        val artists = buildList {
            indexes.index.orEmpty().forEach { idx ->
                idx.artist.orEmpty().forEach { add(it) }
            }
            indexes.child.orEmpty().forEach { add(it) }
        }

        for (artist in artists) {
            val artistId = artist.id ?: continue
            val artistBody = apiGet(session, "getArtist") {
                parameter("id", artistId)
            }
            val artistResp = json.decodeFromString<SubsonicResponse>(artistBody)
                .subsonicResponse.artist ?: continue
            for (album in artistResp.album.orEmpty()) {
                val albumId = album.id ?: continue
                val albumBody = apiGet(session, "getAlbum") {
                    parameter("id", albumId)
                }
                val albumResp = json.decodeFromString<SubsonicResponse>(albumBody)
                    .subsonicResponse.album ?: continue
                for (child in albumResp.song.orEmpty()) {
                    child.toSong(session)?.let { songs += it }
                }
            }
        }
        songs
    }.onFailure { Log.w(TAG, "listAllSongs failed", it) }

    fun streamUrl(session: Session, id: String): String {
        val (token, salt) = tokenPair(session.password)
        return buildString {
            append(session.baseUrl.trimEnd('/'))
            append("/rest/stream.view")
            append("?u=").append(Uri.encode(session.username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=").append(API_VERSION)
            append("&c=").append(Uri.encode(session.clientName))
            append("&id=").append(Uri.encode(id))
        }
    }

    fun coverUrl(session: Session, coverArtId: String?, size: Int = 300): String? {
        if (coverArtId.isNullOrBlank()) return null
        val (token, salt) = tokenPair(session.password)
        return buildString {
            append(session.baseUrl.trimEnd('/'))
            append("/rest/getCoverArt.view")
            append("?u=").append(Uri.encode(session.username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=").append(API_VERSION)
            append("&c=").append(Uri.encode(session.clientName))
            append("&id=").append(Uri.encode(coverArtId))
            append("&size=").append(size)
        }
    }

    private suspend fun apiGet(
        session: Session,
        action: String,
        extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}
    ): String {
        val root = session.baseUrl.trimEnd('/')
        val (token, salt) = tokenPair(session.password)
        val response = http.get("$root/rest/$action.view") {
            parameter("u", session.username)
            parameter("t", token)
            parameter("s", salt)
            parameter("v", API_VERSION)
            parameter("c", session.clientName)
            parameter("f", "json")
            extra()
        }
        if (!response.status.isSuccess()) {
            error("$action HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    private fun SubsonicChild.toSong(session: Session): Song? {
        val sid = id ?: return null
        if (isDir == true) return null
        val stream = streamUrl(session, sid)
        val art = coverUrl(session, coverArt)?.let { Uri.parse(it) }
        return Song(
            id = sid.hashCode().toLong(),
            title = title ?: name,
            artist = artist,
            albumArtist = artist,
            album = album,
            durationMs = duration?.times(1000L)?.takeIf { it > 0 },
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            path = path,
            mimeType = contentType
        )
    }

    private fun tokenPair(password: String): Pair<String, String> {
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5(password + salt)
        return token to salt
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class SubsonicResponse(
        @SerialName("subsonic-response") val subsonicResponse: SubsonicBody
    )

    @Serializable
    private data class SubsonicBody(
        val status: String? = null,
        val version: String? = null,
        val error: SubsonicError? = null,
        val indexes: SubsonicIndexes? = null,
        val artist: SubsonicArtistDetail? = null,
        val album: SubsonicAlbumDetail? = null
    )

    @Serializable
    private data class SubsonicError(
        val code: Int? = null,
        val message: String? = null
    )

    @Serializable
    private data class SubsonicIndexes(
        val index: List<SubsonicIndex>? = null,
        val child: List<SubsonicArtist>? = null
    )

    @Serializable
    private data class SubsonicIndex(
        val name: String? = null,
        val artist: List<SubsonicArtist>? = null
    )

    @Serializable
    private data class SubsonicArtist(
        val id: String? = null,
        val name: String? = null
    )

    @Serializable
    private data class SubsonicArtistDetail(
        val id: String? = null,
        val name: String? = null,
        val album: List<SubsonicAlbumRef>? = null
    )

    @Serializable
    private data class SubsonicAlbumRef(
        val id: String? = null,
        val name: String? = null
    )

    @Serializable
    private data class SubsonicAlbumDetail(
        val id: String? = null,
        val name: String? = null,
        val song: List<SubsonicChild>? = null
    )

    @Serializable
    private data class SubsonicChild(
        val id: String? = null,
        val parent: String? = null,
        val title: String? = null,
        val name: String? = null,
        val album: String? = null,
        val artist: String? = null,
        val track: Int? = null,
        val year: Int? = null,
        val coverArt: String? = null,
        val size: Long? = null,
        val contentType: String? = null,
        val suffix: String? = null,
        val duration: Int? = null,
        val bitRate: Int? = null,
        val path: String? = null,
        val isDir: Boolean? = null,
        val discNumber: Int? = null
    )

    companion object {
        private const val TAG = "SubsonicClient"
        private const val API_VERSION = "1.16.1"
    }
}
