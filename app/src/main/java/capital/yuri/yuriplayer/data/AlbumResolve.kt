package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Build a full [AlbumItem] from catalog by **album name**, not a single albumKey.
 * Used by album detail / MainActivity so a 1-track navigation seed (e.g. Clancy
 * from JF) still expands to every local + remote copy of the release.
 */
suspend fun resolveAlbumItem(
    dao: CatalogDao,
    name: String?,
    artist: String?,
    seedSongs: List<Song> = emptyList()
): AlbumItem? = withContext(Dispatchers.IO) {
    if (name.isNullOrBlank()) return@withContext null
    val key = albumKey(name, artist)
    val expanded = expandAlbumTracksByName(
        dao = dao,
        albumName = name,
        artistName = artist,
        seedKey = key
    )
    val merged = CatalogRepository.dedupeLogicalTracks(expanded + seedSongs)
    if (merged.isEmpty()) return@withContext null
    AlbumItem(
        name = name,
        artist = artist ?: merged.firstOrNull()?.effectiveAlbumArtist,
        trackCount = merged.size,
        songs = merged
    )
}
