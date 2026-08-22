package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full [AlbumItem] for the album page from catalog + optional seed + local index.
 * Never returns fewer songs than a multi-track seed.
 */
suspend fun resolveAlbumItem(
    dao: CatalogDao,
    name: String?,
    artist: String?,
    seedSongs: List<Song> = emptyList(),
    library: LibraryIndex? = null
): AlbumItem? = withContext(Dispatchers.IO) {
    if (name.isNullOrBlank() && seedSongs.isEmpty()) return@withContext null
    val albumName = name ?: seedSongs.firstOrNull()?.album
    val artistName = artist ?: seedSongs.firstOrNull()?.effectiveAlbumArtist
    if (albumName.isNullOrBlank()) return@withContext null

    val key = albumKey(albumName, artistName)
    AlbumLog.i(albumName, "resolve artist='$artistName' key='$key' seed=${seedSongs.size}")
    AlbumLog.songs(albumName, "resolve.seed", seedSongs)

    val expanded = expandAlbumTracksByName(
        dao = dao,
        albumName = albumName,
        artistName = artistName,
        seedKey = key,
        extraSeedSongs = seedSongs
    )
    AlbumLog.songs(albumName, "resolve.expanded", expanded)
    val localSongs = library?.let { findLocalAlbum(it, albumName, artistName)?.songs }.orEmpty()
    AlbumLog.songs(albumName, "resolve.local", localSongs)
    val songs = dedupeAlbumPageTracks(expanded + seedSongs + localSongs)
    AlbumLog.i(albumName, "resolve done n=${songs.size} expanded=${expanded.size} seed=${seedSongs.size} local=${localSongs.size}")
    if (songs.size < seedSongs.size && seedSongs.size > 1) {
        AlbumLog.w(albumName, "RESOLVE SHRANK seed=${seedSongs.size} → ${songs.size}")
    }
    if (songs.isEmpty()) return@withContext null

    AlbumItem(
        name = albumName,
        artist = artistName ?: songs.firstOrNull()?.effectiveAlbumArtist,
        trackCount = songs.size,
        songs = songs
    )
}
