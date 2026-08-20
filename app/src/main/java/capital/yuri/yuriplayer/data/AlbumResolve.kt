package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Build a full [AlbumItem] from catalog by **album name**, not a single albumKey.
 *
 * Multi-source copies of the same track collapse via [CatalogRepository.dedupeLogicalTracks].
 * Different tracks on the same release stay distinct. Never returns fewer songs than [seedSongs]
 * when the seed already looked complete (guards the Clancy "full → one track" flash).
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

    val merged = CatalogRepository.dedupeLogicalTracks(expanded + seedSongs)
    if (merged.isEmpty()) return@withContext null

    // Never shrink a multi-track seed to a single-track expand glitch
    val songs = if (seedSongs.size > 1 && merged.size < seedSongs.size) {
        CatalogRepository.dedupeLogicalTracks(seedSongs + merged)
    } else {
        merged
    }

    AlbumItem(
        name = albumName,
        artist = artistName ?: songs.firstOrNull()?.effectiveAlbumArtist,
        trackCount = songs.size,
        songs = songs
    )
}
