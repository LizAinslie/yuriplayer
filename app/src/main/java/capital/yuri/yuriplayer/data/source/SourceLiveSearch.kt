package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceSearchHits(
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList()
)

/**
 * Per-library search: query the server, then swap in indexed rows when we
 * already have them so playback/metadata stay consistent.
 */
class SourceLiveSearch(
    private val instances: SourceInstanceRepository,
    private val jellyfin: JellyfinClient,
    private val subsonic: SubsonicClient,
    private val catalog: CatalogRepository
) {
    suspend fun search(
        sourceType: String,
        instanceId: Long?,
        query: String,
        limit: Int = 40
    ): SourceSearchHits = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SourceSearchHits()
        when (SourceType.from(sourceType)) {
            SourceType.JELLYFIN -> jellyfinSearch(instanceId, q, limit)
            SourceType.SUBSONIC, SourceType.NAVIDROME -> subsonicSearch(instanceId, q, limit)
            else -> localSearch(sourceType, instanceId, q, limit)
        }
    }

    private suspend fun localSearch(
        sourceType: String,
        instanceId: Long?,
        query: String,
        limit: Int
    ): SourceSearchHits {
        val songs = catalog.searchSongsForSource(sourceType, instanceId, query, limit)
        return SourceSearchHits(
            songs = songs,
            albums = albumsFromSongs(songs),
            artists = artistsFromSongs(songs)
        )
    }

    private suspend fun jellyfinSearch(
        instanceId: Long?,
        query: String,
        limit: Int
    ): SourceSearchHits {
        val row = instanceId?.let { instances.get(it) } ?: return SourceSearchHits()
        val session = openJellyfin(row) ?: return SourceSearchHits()
        val remoteSongs = jellyfin.searchAudio(session, query, limit).getOrDefault(emptyList())
        val songs = matchSongs(listOf(CatalogSources.JELLYFIN), instanceId, remoteSongs)
        val albumHits = jellyfin.searchAlbums(session, query, 16).getOrDefault(emptyList())
        val albums = albumHits.map { hit ->
            AlbumItem(hit.name, hit.artist, hit.trackCount, emptyList())
        }.ifEmpty { albumsFromSongs(songs) }
        val artistHits = jellyfin.searchMusicArtists(session, query, 16).getOrDefault(emptyList())
        val artists = artistHits.map { hit ->
            ArtistItem(hit.name, 0, 0, emptyList())
        }.ifEmpty { artistsFromSongs(songs) }
        return SourceSearchHits(songs = songs, albums = albums, artists = artists)
    }

    private suspend fun subsonicSearch(
        instanceId: Long?,
        query: String,
        limit: Int
    ): SourceSearchHits {
        val row = instanceId?.let { instances.get(it) } ?: return SourceSearchHits()
        val session = openSubsonic(row) ?: return SourceSearchHits()
        val catalogType = when (SourceType.from(row.type)) {
            SourceType.NAVIDROME -> CatalogSources.NAVIDROME
            else -> CatalogSources.SUBSONIC
        }
        val hits = subsonic.searchLibrary(session, query, songCount = limit).getOrNull()
            ?: return SourceSearchHits()
        val songs = matchSongs(
            listOf(catalogType, CatalogSources.SUBSONIC, CatalogSources.NAVIDROME).distinct(),
            instanceId,
            hits.songs
        )
        val albums = hits.albums.map { hit ->
            AlbumItem(hit.name, hit.artist, hit.songCount ?: 0, emptyList())
        }.ifEmpty { albumsFromSongs(songs) }
        val artists = hits.artists.map { hit ->
            ArtistItem(hit.name, 0, 0, emptyList())
        }.ifEmpty { artistsFromSongs(songs) }
        return SourceSearchHits(songs = songs, albums = albums, artists = artists)
    }

    private suspend fun matchSongs(
        sourceTypes: List<String>,
        instanceId: Long?,
        remote: List<Song>
    ): List<Song> {
        val ids = remote.mapNotNull { it.path }
        val indexed = LinkedHashMap<String, Song>()
        for (type in sourceTypes) {
            indexed.putAll(catalog.songsByExternalIds(type, instanceId, ids))
        }
        return remote.map { song -> indexed[song.path] ?: song }
    }

    private fun albumsFromSongs(songs: List<Song>): List<AlbumItem> =
        songs.filter { !it.album.isNullOrBlank() }
            .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
            .map { (_, tracks) ->
                AlbumItem(
                    name = tracks.first().album,
                    artist = tracks.first().effectiveAlbumArtist,
                    trackCount = tracks.size,
                    songs = tracks
                )
            }

    private fun artistsFromSongs(songs: List<Song>): List<ArtistItem> =
        songs.groupBy { it.effectiveAlbumArtist?.lowercase() ?: it.displayArtist.lowercase() }
            .map { (_, tracks) ->
                ArtistItem(
                    name = tracks.first().effectiveAlbumArtist ?: tracks.first().displayArtist,
                    trackCount = tracks.size,
                    albumCount = tracks.map { it.album }.distinct().size,
                    songs = tracks
                )
            }

    private suspend fun openJellyfin(row: SourceInstanceEntity): JellyfinClient.Session? {
        val url = row.baseUrl ?: return null
        val user = row.username ?: return null
        val secret = row.secret ?: return null
        return jellyfin.authenticate(url, user, secret).getOrNull()
    }

    private fun openSubsonic(row: SourceInstanceEntity): SubsonicClient.Session? {
        val url = row.baseUrl ?: return null
        val user = row.username ?: return null
        val secret = row.secret ?: return null
        return SubsonicClient.Session(
            baseUrl = SourceInstanceRepository.normalizeBaseUrl(url),
            username = user,
            password = secret
        )
    }
}
