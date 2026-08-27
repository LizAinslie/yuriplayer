package capital.yuri.yuriplayer.data.source

import android.net.Uri
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.http.UrlScope
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
    }.onFailure { log.w { "ping failed: ${it.message}" } }

    /**
     * Paged album index via getAlbumList2 (alphabeticalByName).
     * [offset] is the album-list cursor for resume.
     */
    suspend fun listAlbumsPage(
        session: Session,
        offset: Int = 0,
        pageSize: Int = 100,
        type: String = "alphabeticalByName"
    ): Result<AlbumPage> = runCatching {
        val body = apiGet(session, "getAlbumList2") {
            param("type", type)
            param("size", pageSize.coerceIn(1, 500))
            param("offset", offset.coerceAtLeast(0))
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
    }.onFailure { log.w { "listAlbumsPage failed: ${it.message}" } }

    suspend fun listSongsForAlbum(session: Session, albumId: String): Result<List<Song>> =
        runCatching {
            val body = apiGet(session, "getAlbum") {
                param("id", albumId)
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
        }.onFailure { log.w { "listSongsForAlbum($albumId) failed: ${it.message}" } }

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
                    log.w { "getAlbum failed ${album.id}: ${it.message}" }
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
    }.onFailure { log.w { "listSongsPaged failed: ${it.message}" } }

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
                param("id", seedId)
                param("count", count.coerceIn(1, 200))
            }
        }.getOrElse {
            if (action == "getSimilarSongs2") {
                apiGet(session, "getSimilarSongs") {
                    param("id", seedId)
                    param("count", count.coerceIn(1, 200))
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
    }.onFailure { log.w { "similarSongs failed: ${it.message}" } }

    data class LibraryHits(
        val songs: List<Song>,
        val albums: List<AlbumRef>,
        val artists: List<ArtistHit>
    )

    suspend fun searchLibrary(
        session: Session,
        query: String,
        songCount: Int = 40,
        albumCount: Int = 20,
        artistCount: Int = 20
    ): Result<LibraryHits> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching LibraryHits(emptyList(), emptyList(), emptyList())
        val body = apiGet(session, "search3") {
            param("query", q)
            param("songCount", songCount.coerceIn(0, 100))
            param("albumCount", albumCount.coerceIn(0, 50))
            param("artistCount", artistCount.coerceIn(0, 50))
        }
        val result = json.decodeFromString<SubsonicResponse>(body).subsonicResponse.searchResult3
        val songs = result?.song.orEmpty().mapNotNull { it.toSong(session) }
        val albums = result?.album.orEmpty().mapNotNull { a ->
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
        val artists = result?.artist.orEmpty().mapNotNull { a ->
            val id = a.id ?: return@mapNotNull null
            val name = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArtistHit(id = id, name = name, coverArt = a.coverArt)
        }
        LibraryHits(songs = songs, albums = albums, artists = artists)
    }.onFailure { log.w { "searchLibrary failed: ${it.message}" } }

    suspend fun searchArtists(
        session: Session,
        query: String,
        count: Int = 12
    ): Result<List<ArtistHit>> = runCatching {
        searchLibrary(session, query, songCount = 0, albumCount = 0, artistCount = count)
            .getOrThrow()
            .artists
    }.onFailure { log.w { "searchArtists failed: ${it.message}" } }

    data class ArtistHit(
        val id: String,
        val name: String,
        val coverArt: String?
    )

    data class PlaylistRef(
        val id: String,
        val name: String,
        val songCount: Int,
        val coverArt: String?,
        val owner: String? = null
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
                coverArt = p.coverArt,
                owner = p.owner
            )
        }
    }.onFailure { log.w { "listPlaylists failed: ${it.message}" } }

    suspend fun playlistSongs(session: Session, playlistId: String): Result<List<Song>> =
        runCatching {
            val body = apiGet(session, "getPlaylist") {
                param("id", playlistId)
            }
            val detail = json.decodeFromString<SubsonicResponse>(body).subsonicResponse.playlist
            detail?.entry.orEmpty().mapNotNull { it.toSong(session, albumCoverArt = detail?.coverArt) }
        }.onFailure { log.w { "playlistSongs failed: ${it.message}" } }

    fun streamUrl(session: Session, id: String): String =
        restUrl(session, "stream") {
            param("id", id)
            param("format", "raw")
        }

    fun coverUrl(session: Session, coverArtId: String?, size: Int = 300): String? {
        if (coverArtId.isNullOrBlank()) return null
        return restUrl(session, "getCoverArt") {
            param("id", coverArtId)
            param("size", size)
        }
    }

    private fun restUrl(
        session: Session,
        action: String,
        extra: UrlScope.() -> Unit = {}
    ): String {
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
            contentUri = stream,
            albumArtUri = art?.toString(),
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            genre = genreStr,
            path = "subsonic:$sid",
            mimeType = contentType ?: suffix?.let { "audio/$it" },
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
        val artist: List<SubsonicArtist>? = null,
        val album: List<SubsonicAlbumRef>? = null,
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
        private val log = yuriLog("SubsonicClient")
        /** 1.16.1 is widely implemented; OpenSubsonic servers accept it. */
        private const val API_VERSION = "1.16.1"
    }
}
