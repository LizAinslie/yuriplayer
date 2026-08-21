package capital.yuri.yuriplayer.core.source

import capital.yuri.yuriplayer.core.http.UrlScope
import capital.yuri.yuriplayer.core.http.normalizeBaseUrl
import capital.yuri.yuriplayer.core.http.url
import capital.yuri.yuriplayer.core.library.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

class SubsonicCatalog(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    data class Session(
        val baseUrl: String,
        val username: String,
        val password: String,
        val clientName: String = "YuriPlayer"
    )

    suspend fun ping(account: RemoteAccount): Result<RemoteAccount> = runCatching {
        val session = sessionOf(account)
        val body = apiGet(session, "ping")
        val resp = json.decodeFromString<SubsonicResponse>(body).subsonicResponse
        if (resp.status != "ok") error(resp.error?.message ?: "Could not reach server")
        account.copy(
            name = account.name.ifBlank {
                resp.type?.replaceFirstChar { it.uppercase() } ?: "Subsonic"
            },
            baseUrl = session.baseUrl
        )
    }

    suspend fun listTracks(
        account: RemoteAccount,
        maxSongs: Int = 80_000,
        startAlbumOffset: Int = 0,
        onPage: suspend (page: List<Track>, albumOffset: Int, complete: Boolean) -> Unit = { _, _, _ -> }
    ): Result<List<Track>> =
        runCatching {
            val session = sessionOf(account)
            val out = ArrayList<Track>(512)
            var offset = startAlbumOffset.coerceAtLeast(0)
            val pageSize = 100
            while (out.size < maxSongs) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val page = listAlbums(session, offset, pageSize, type = "alphabeticalByName")
                if (page.isEmpty()) break
                val batch = ArrayList<Track>()
                for (album in page) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val id = album.id ?: continue
                    batch += songsForAlbum(session, id, album, account.id)
                    if (out.size + batch.size >= maxSongs) break
                }
                out += batch
                offset += page.size
                val done = page.size < pageSize
                onPage(batch, offset, done)
                if (done) break
            }
            out
        }

    suspend fun listNewestTracks(account: RemoteAccount, maxAlbums: Int = 40): Result<List<Track>> =
        runCatching {
            val session = sessionOf(account)
            val albums = listAlbums(session, offset = 0, size = maxAlbums, type = "newest")
            val out = ArrayList<Track>(albums.size * 12)
            for (album in albums) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val id = album.id ?: continue
                out += songsForAlbum(session, id, album, account.id)
            }
            out
        }

    private suspend fun listAlbums(
        session: Session,
        offset: Int,
        size: Int,
        type: String = "alphabeticalByName"
    ): List<SubsonicAlbum> {
        val body = apiGet(session, "getAlbumList2") {
            param("type", type)
            param("size", size)
            param("offset", offset)
        }
        return json.decodeFromString<SubsonicResponse>(body)
            .subsonicResponse.albumList2?.album.orEmpty()
    }

    private suspend fun songsForAlbum(
        session: Session,
        albumId: String,
        albumRef: SubsonicAlbum,
        sourceId: String
    ): List<Track> {
        val body = apiGet(session, "getAlbum") { param("id", albumId) }
        val album = json.decodeFromString<SubsonicResponse>(body).subsonicResponse.album
            ?: return emptyList()
        val cover = coverUrl(session, album.coverArt ?: album.id ?: albumRef.coverArt)
        val albumName = album.name ?: albumRef.name
        val albumArtist = album.albumArtist ?: album.artist ?: albumRef.artist
        return album.song.orEmpty().mapNotNull { child ->
            val sid = child.id ?: return@mapNotNull null
            if (child.isDir == true) return@mapNotNull null
            Track(
                id = "subsonic:$sid",
                uri = streamUrl(session, sid),
                title = child.title ?: child.name,
                artist = child.artist ?: albumArtist,
                albumArtist = albumArtist,
                album = child.album ?: albumName,
                durationMs = child.duration?.times(1000L),
                trackNumber = child.track,
                discNumber = child.discNumber,
                year = child.year ?: album.year,
                genre = child.genre,
                artworkUri = coverUrl(session, child.coverArt) ?: cover,
                sourceId = sourceId
            )
        }
    }

    suspend fun searchTracks(account: RemoteAccount, query: String, limit: Int = 80): Result<List<Track>> =
        runCatching {
            val session = sessionOf(account)
            val body = apiGet(session, "search3") {
                param("query", query)
                param("songCount", limit)
                param("albumCount", 12)
                param("artistCount", 12)
            }
            val songs = json.decodeFromString<SubsonicResponse>(body)
                .subsonicResponse.searchResult3?.song.orEmpty()
            songs.mapNotNull { child ->
                if (child.isDir == true) return@mapNotNull null
                val sid = child.id ?: return@mapNotNull null
                Track(
                    id = "subsonic:$sid",
                    uri = streamUrl(session, sid),
                    title = child.title ?: child.name,
                    artist = child.artist,
                    albumArtist = child.artist,
                    album = child.album,
                    durationMs = child.duration?.times(1000L),
                    trackNumber = child.track,
                    discNumber = child.discNumber,
                    year = child.year,
                    genre = child.genre,
                    artworkUri = coverUrl(session, child.coverArt),
                    sourceId = account.id
                )
            }
        }

    suspend fun searchArtistImages(account: RemoteAccount, query: String): Result<List<capital.yuri.yuriplayer.core.artist.ArtistImageCandidate>> =
        runCatching {
            val session = sessionOf(account)
            val body = apiGet(session, "search3") {
                param("query", query)
                param("songCount", 0)
                param("albumCount", 0)
                param("artistCount", 12)
            }
            val artists = json.decodeFromString<SubsonicResponse>(body)
                .subsonicResponse.searchResult3?.artist.orEmpty()
            artists.mapNotNull { a ->
                val cover = a.coverArt ?: return@mapNotNull null
                val name = a.name ?: return@mapNotNull null
                capital.yuri.yuriplayer.core.artist.ArtistImageCandidate(
                    url = coverUrl(session, cover, 1200) ?: return@mapNotNull null,
                    sourceId = "navidrome",
                    label = "Navidrome · ${account.name} · $name",
                    width = 1200,
                    height = 1200
                )
            }
        }

    fun streamUrl(session: Session, id: String): String =
        restUrl(session, "stream") {
            param("id", id)
            param("format", "raw")
        }

    fun coverUrl(session: Session, coverArtId: String?, size: Int = 600): String? {
        if (coverArtId.isNullOrBlank()) return null
        return restUrl(session, "getCoverArt") {
            param("id", coverArtId)
            param("size", size)
        }
    }

    private fun sessionOf(account: RemoteAccount) = Session(
        baseUrl = normalizeBaseUrl(account.baseUrl),
        username = account.username,
        password = account.secret
    )

    private fun restUrl(session: Session, action: String, extra: UrlScope.() -> Unit = {}): String {
        val (token, salt) = tokenPair(session.password)
        return url(session.baseUrl) {
            path("rest", "$action.view")
            param("u", session.username)
            param("t", token)
            param("s", salt)
            param("v", API_VERSION)
            param("c", session.clientName)
            extra()
        }
    }

    private suspend fun apiGet(
        session: Session,
        action: String,
        extra: UrlScope.() -> Unit = {}
    ): String {
        val requestUrl = restUrl(session, action) {
            param("f", "json")
            extra()
        }
        val response = http.get(requestUrl)
        if (!response.status.isSuccess()) error("$action HTTP ${response.status.value}")
        return response.bodyAsText()
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
        val type: String? = null,
        val error: SubsonicError? = null,
        val albumList2: SubsonicAlbumList? = null,
        val album: SubsonicAlbumDetail? = null,
        val searchResult3: SubsonicSearchResult? = null
    )

    @Serializable
    private data class SubsonicSearchResult(
        val song: List<SubsonicChild> = emptyList(),
        val artist: List<SubsonicArtistRef> = emptyList()
    )

    @Serializable
    private data class SubsonicArtistRef(
        val id: String? = null,
        val name: String? = null,
        val coverArt: String? = null
    )

    @Serializable
    private data class SubsonicError(val message: String? = null)

    @Serializable
    private data class SubsonicAlbumList(val album: List<SubsonicAlbum> = emptyList())

    @Serializable
    private data class SubsonicAlbum(
        val id: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val coverArt: String? = null,
        val year: Int? = null
    )

    @Serializable
    private data class SubsonicAlbumDetail(
        val id: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val coverArt: String? = null,
        val year: Int? = null,
        val song: List<SubsonicChild> = emptyList()
    )

    @Serializable
    private data class SubsonicChild(
        val id: String? = null,
        val title: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverArt: String? = null,
        val duration: Int? = null,
        val track: Int? = null,
        val discNumber: Int? = null,
        val year: Int? = null,
        val genre: String? = null,
        val isDir: Boolean? = null
    )

    companion object {
        private const val API_VERSION = "1.16.1"
    }
}
