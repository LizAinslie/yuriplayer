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
 * Subsonic / OpenSubsonic REST client (JSON).
 *
 * Auth: u + t=md5(password+salt) + s + v + c + f=json
 * Large libraries: prefer [listSongsPaged] (getAlbumList2 + getAlbum) over
 * walking getIndexes for every artist.
 */
class SubsonicClient(
    private val http: HttpClient,
    private val json: Json
) {
    data class Session(
        val baseUrl: String,
        val username: String,
        val password: String,
        val clientName: String = "YuriPlayer",
        val openSubsonic: Boolean = false,
        val serverVersion: String? = null
    )

    data class AlbumPage(
        val albums: List<AlbumRef>,
        val offset: Int,
        /** True when the server returned fewer than [pageSize] albums. */
        val exhausted: Boolean
    )

    data class AlbumRef(
        val id: String,
        val name: String?,
        val artist: String?,
        val coverArt: String?,
        val year: Int? = null,
        val songCount: Int? = null
    )

    suspend fun ping(session: Session): Result<Session> = runCatching {
        val body = apiGet(session, "ping")
        val root = json.decodeFromString<SubsonicResponse>(body)
        val resp = root.subsonicResponse
        if (resp.status != "ok") {
            error(resp.error?.message ?: "ping failed")
        }
        val open = resp.openSubsonic == true ||
            resp.type?.contains("navidrome", ignoreCase = true) == true ||
            resp.serverVersion?.contains("navidrome", ignoreCase = true) == true
        session.copy(
            openSubsonic = open,
            serverVersion = resp.version ?: resp.serverVersion
        )
    }.onFailure { Log.w(TAG, "ping failed: ${it.message}") }

    /**
     * Paged album index via getAlbumList2 (alphabeticalByName).
     * [offset] is the album-list cursor for resume.
     */
    suspend fun listAlbumsPage(
        session: Session,
        offset: Int = 0,
        pageSize: Int = 100
    ): Result<AlbumPage> = runCatching {
        val body = apiGet(session, "getAlbumList2") {
            parameter("type", "alphabeticalByName")
            parameter("size", pageSize.coerceIn(1, 500))
            parameter("offset", offset.coerceAtLeast(0))
        }
        val list = json.decodeFromString<SubsonicResponse>(body)
            .subsonicResponse.albumList2?.album.orEmpty()
        val refs = list.mapNotNull { a ->
            val id = a.id ?: return@mapNotNull null
            AlbumRef(
                id = id,
                name = a.name ?: a.album,
                artist = a.artist,
                coverArt = a.coverArt,
                year = a.year,
                songCount = a.songCount
            )
        }
        AlbumPage(
            albums = refs,
            offset = offset,
            exhausted = refs.size < pageSize
        )
    }.onFailure { Log.w(TAG, "listAlbumsPage failed: ${it.message}") }

    suspend fun listSongsForAlbum(session: Session, albumId: String): Result<List<Song>> =
        runCatching {
            val body = apiGet(session, "getAlbum") {
                parameter("id", albumId)
            }
            val album = json.decodeFromString<SubsonicResponse>(body)
                .subsonicResponse.album ?: return@runCatching emptyList()
            album.song.orEmpty().mapNotNull {
                it.toSong(
                    session,
                    albumCoverArt = album.coverArt ?: album.id,
                    albumNameFallback = album.name,
                    albumArtistFallback = album.resolvedAlbumArtist()
                )
            }
        }.onFailure { Log.w(TAG, "listSongsForAlbum($albumId) failed: ${it.message}") }

    /**
     * Streams all songs via album list paging. Prefer this for large Navidrome libraries.
     * [startAlbumOffset] resumes the getAlbumList2 cursor.
     */
    suspend fun listSongsPaged(
        session: Session,
        pageSize: Int = 100,
        startAlbumOffset: Int = 0,
        maxSongs: Int = 100_000,
        onPage: suspend (
            songs: List<Song>,
            albumOffset: Int,
            albumsInPage: Int,
            exhausted: Boolean,
            albumFetchFailed: Boolean
        ) -> Unit
    ): Result<Int> = runCatching {
        var offset = startAlbumOffset.coerceAtLeast(0)
        var delivered = 0
        while (delivered < maxSongs) {
            val page = listAlbumsPage(session, offset = offset, pageSize = pageSize).getOrThrow()
            if (page.albums.isEmpty()) {
                onPage(emptyList(), offset, 0, true, false)
                break
            }
            val batch = mutableListOf<Song>()
            var albumFetchFailed = false
            for (album in page.albums) {
                val songs = listSongsForAlbum(session, album.id).getOrElse {
                    Log.w(TAG, "getAlbum failed ${album.id}: ${it.message}")
                    albumFetchFailed = true
                    emptyList()
                }
                batch += songs
            }
            if (batch.isNotEmpty()) {
                onPage(batch, offset, page.albums.size, page.exhausted, albumFetchFailed)
                delivered += batch.size
            } else {
                onPage(emptyList(), offset, page.albums.size, page.exhausted, albumFetchFailed)
            }
            offset += page.albums.size
            if (page.exhausted) break
        }
        delivered
    }.onFailure { Log.w(TAG, "listSongsPaged failed: ${it.message}") }

    /**
     * Legacy full walk via indexes (small libraries / servers without albumList2).
     */
    suspend fun listAllSongs(session: Session): Result<List<Song>> = runCatching {
        val out = mutableListOf<Song>()
        listSongsPaged(session) { songs, _, _, _, _ ->
            out += songs
        }.getOrThrow()
        out
    }

    /**
     * OpenSubsonic / Subsonic similar tracks for radio discovery.
     * Uses getSimilarSongs2 when available, falls back to getSimilarSongs.
     */
    suspend fun similarSongs(
        session: Session,
        seedId: String,
        count: Int = 50
    ): Result<List<Song>> = runCatching {
        val action = if (session.openSubsonic) "getSimilarSongs2" else "getSimilarSongs"
        val body = runCatching {
            apiGet(session, action) {
                parameter("id", seedId)
                parameter("count", count.coerceIn(1, 200))
            }
        }.getOrElse {
            if (action == "getSimilarSongs2") {
                apiGet(session, "getSimilarSongs") {
                    parameter("id", seedId)
                    parameter("count", count.coerceIn(1, 200))
                }
            } else throw it
        }
        val resp = json.decodeFromString<SubsonicResponse>(body).subsonicResponse
        if (resp.status != null && resp.status != "ok") {
            error(resp.error?.message ?: "similarSongs failed")
        }
        val children = resp.similarSongs2?.song
            ?: resp.similarSongs?.song
            ?: emptyList()
        children.mapNotNull { it.toSong(session) }
    }.onFailure { Log.w(TAG, "similarSongs failed: ${it.message}") }

    suspend fun searchArtists(
        session: Session,
        query: String,
        count: Int = 12
    ): Result<List<ArtistHit>> = runCatching {
        val body = apiGet(session, "search3") {
            parameter("query", query)
            parameter("artistCount", count.coerceIn(1, 40))
            parameter("albumCount", 0)
            parameter("songCount", 0)
        }
        val artists = json.decodeFromString<SubsonicResponse>(body)
            .subsonicResponse.searchResult3?.artist.orEmpty()
        artists.mapNotNull { a ->
            val id = a.id ?: return@mapNotNull null
            val name = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArtistHit(
                id = id,
                name = name,
                coverArt = a.coverArt
            )
        }
    }.onFailure { Log.w(TAG, "searchArtists failed: ${it.message}") }

    data class ArtistHit(
        val id: String,
        val name: String,
        val coverArt: String?
    )

    data class PlaylistRef(
        val id: String,
        val name: String,
        val songCount: Int,
        val coverArt: String?
    )

    suspend fun listPlaylists(session: Session): Result<List<PlaylistRef>> = runCatching {
        val body = apiGet(session, "getPlaylists")
        val lists = json.decodeFromString<SubsonicResponse>(body)
            .subsonicResponse.playlists?.playlist.orEmpty()
        lists.mapNotNull { p ->
            val id = p.id ?: return@mapNotNull null
            val name = p.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlaylistRef(
                id = id,
                name = name,
                songCount = p.songCount ?: 0,
                coverArt = p.coverArt
            )
        }
    }.onFailure { Log.w(TAG, "listPlaylists failed: ${it.message}") }

    suspend fun playlistSongs(session: Session, playlistId: String): Result<List<Song>> =
        runCatching {
            val body = apiGet(session, "getPlaylist") {
                parameter("id", playlistId)
            }
            val detail = json.decodeFromString<SubsonicResponse>(body).subsonicResponse.playlist
            detail?.entry.orEmpty().mapNotNull { it.toSong(session, albumCoverArt = detail.coverArt) }
        }.onFailure { Log.w(TAG, "playlistSongs failed: ${it.message}") }

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
            append("&format=raw")
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

    private fun SubsonicAlbumDetail.resolvedAlbumArtist(): String? =
        albumArtist?.takeIf { it.isNotBlank() }
            ?: displayArtist?.takeIf { it.isNotBlank() }
            ?: artist?.takeIf { it.isNotBlank() }
            ?: artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }

    private fun SubsonicChild.toSong(
        session: Session,
        albumCoverArt: String? = null,
        albumNameFallback: String? = null,
        albumArtistFallback: String? = null
    ): Song? {
        val sid = id ?: return null
        if (isDir == true) return null
        val stream = streamUrl(session, sid)
        val artId = coverArt ?: albumCoverArt ?: parent
        val art = coverUrl(session, artId, size = 600)?.let { Uri.parse(it) }
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
        val genreStr = genre?.takeIf { it.isNotBlank() }
        val structuredArtists = artists.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
        val albumArtistName = albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtistFallback?.takeIf { it.isNotBlank() }
        val extras = structuredArtists.filter { name ->
            albumArtistName == null || !name.equals(albumArtistName, ignoreCase = true)
        }
        val trackArtistName = when {
            extras.isNotEmpty() && !albumArtistName.isNullOrBlank() ->
                extras.joinToString("; ")
            structuredArtists.isNotEmpty() -> structuredArtists.joinToString("; ")
            else -> artist?.takeIf { it.isNotBlank() }
        }
        val mbArtist = artists?.firstOrNull { !it.musicBrainzId.isNullOrBlank() }?.musicBrainzId
            ?: musicBrainzArtistId
            ?: musicBrainzId
        return Song(
            id = sid.hashCode().toLong(),
            title = title,
            artist = trackArtistName,
            albumArtist = albumArtistName ?: trackArtistName,
            album = album?.takeIf { it.isNotBlank() } ?: albumNameFallback?.takeIf { it.isNotBlank() },
            durationMs = duration?.times(1000L)?.takeIf { it > 0 },
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            genre = genreStr,
            path = "subsonic:$sid",
            mimeType = contentType,
            explicit = genreStr?.contains("explicit", ignoreCase = true) == true,
            musicBrainzArtistId = mbArtist?.takeIf { it.isNotBlank() }
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
        val type: String? = null,
        val serverVersion: String? = null,
        val openSubsonic: Boolean? = null,
        val error: SubsonicError? = null,
        val indexes: SubsonicIndexes? = null,
        val artist: SubsonicArtistDetail? = null,
        val album: SubsonicAlbumDetail? = null,
        val albumList2: SubsonicAlbumList2? = null,
        val similarSongs: SubsonicSongList? = null,
        val similarSongs2: SubsonicSongList? = null,
        val searchResult3: SubsonicSearchResult3? = null,
        val playlists: SubsonicPlaylists? = null,
        val playlist: SubsonicPlaylistDetail? = null
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
        val name: String? = null,
        val coverArt: String? = null
    )

    @Serializable
    private data class SubsonicArtistDetail(
        val id: String? = null,
        val name: String? = null,
        val coverArt: String? = null,
        val album: List<SubsonicAlbumRef>? = null
    )

    @Serializable
    private data class SubsonicAlbumRef(
        val id: String? = null,
        val name: String? = null,
        val album: String? = null,
        val artist: String? = null,
        val coverArt: String? = null,
        val year: Int? = null,
        val songCount: Int? = null
    )

    @Serializable
    private data class SubsonicAlbumList2(
        val album: List<SubsonicAlbumRef>? = null
    )

    @Serializable
    private data class SubsonicAlbumDetail(
        val id: String? = null,
        val name: String? = null,
        val coverArt: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val displayArtist: String? = null,
        val artists: List<SubsonicArtistRef>? = null,
        val song: List<SubsonicChild>? = null
    )

    @Serializable
    private data class SubsonicSongList(
        val song: List<SubsonicChild>? = null
    )

    @Serializable
    private data class SubsonicPlaylists(
        val playlist: List<SubsonicPlaylistRef>? = null
    )

    @Serializable
    private data class SubsonicPlaylistRef(
        val id: String? = null,
        val name: String? = null,
        val songCount: Int? = null,
        val coverArt: String? = null,
        val owner: String? = null
    )

    @Serializable
    private data class SubsonicPlaylistDetail(
        val id: String? = null,
        val name: String? = null,
        val songCount: Int? = null,
        val coverArt: String? = null,
        val entry: List<SubsonicChild>? = null
    )

    @Serializable
    private data class SubsonicSearchResult3(
        val artist: List<SubsonicArtist>? = null
    )

    @Serializable
    private data class SubsonicChild(
        val id: String? = null,
        val parent: String? = null,
        val title: String? = null,
        val name: String? = null,
        val album: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val track: Int? = null,
        val year: Int? = null,
        val genre: String? = null,
        val coverArt: String? = null,
        val size: Long? = null,
        val contentType: String? = null,
        val suffix: String? = null,
        val duration: Int? = null,
        val bitRate: Int? = null,
        val path: String? = null,
        val isDir: Boolean? = null,
        val discNumber: Int? = null,
        val musicBrainzId: String? = null,
        val musicBrainzArtistId: String? = null,
        val artists: List<SubsonicArtistRef>? = null
    )

    @Serializable
    private data class SubsonicArtistRef(
        val id: String? = null,
        val name: String? = null,
        val artistImageUrl: String? = null,
        val musicBrainzId: String? = null
    )

    companion object {
        private const val TAG = "SubsonicClient"
        /** 1.16.1 is widely implemented; OpenSubsonic servers accept it. */
        private const val API_VERSION = "1.16.1"
    }
}
