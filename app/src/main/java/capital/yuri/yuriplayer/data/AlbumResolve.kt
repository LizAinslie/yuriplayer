package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full [AlbumItem] for the album page from catalog + optional seed.
 * Never returns fewer songs than a multi-track seed.
 */
suspend fun resolveAlbumItem(
    dao: CatalogDao,
    name: String?,
    artist: String?,
    seedSongs: List<Song> = emptyList()
): AlbumItem? = withContext(Dispatchers.IO) {
    if (name.isNullOrBlank() && seedSongs.isEmpty()) return@withContext null
    val albumName = name ?: seedSongs.firstOrNull()?.album
    val artistName = artist ?: seedSongs.firstOrNull()?.effectiveAlbumArtist
    if (albumName.isNullOrBlank()) return@withContext null

    val key = albumKey(albumName, artistName)
    val expanded = expandAlbumTracksByName(
        dao = dao,
        albumName = albumName,
        artistName = artistName,
        seedKey = key,
        extraSeedSongs = seedSongs
    )

    // Absolute union — never lose seed tracks
    val songs = dedupeAlbumPageTracks(expanded + seedSongs)
    if (songs.isEmpty()) return@withContext null

    AlbumItem(
        name = albumName,
        artist = artistName ?: songs.firstOrNull()?.effectiveAlbumArtist,
        trackCount = songs.size,
        songs = songs
    )
}
