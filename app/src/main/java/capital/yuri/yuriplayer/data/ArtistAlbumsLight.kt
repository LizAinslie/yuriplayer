package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast discography for the artist page.
 *
 * Full [CatalogRepository.albumItemsForArtist] expands every release (slow on
 * large artists + multi-source). The horizontal discography row only needs
 * name / year / trackCount / one seed for art — open the album page for the
 * full track list.
 */
suspend fun lightAlbumItemsForArtist(
    dao: CatalogDao,
    artistKey: String
): List<AlbumItem> = withContext(Dispatchers.IO) {
    if (artistKey.isBlank()) return@withContext emptyList()

    val rows = dao.getAlbumsForArtist(artistKey)
    if (rows.isNotEmpty()) {
        return@withContext rows.map { row ->
            val seed = dao.getOneTrackForAlbum(row.albumKey)?.toLightSong()
            AlbumItem(
                name = row.name,
                artist = row.artist,
                trackCount = row.trackCount,
                songs = listOfNotNull(seed)
            )
        }.sortedWith(
            compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }

    // No rollup rows yet — group tracks lightly without multi-key expand
    dao.getTracksForArtist(artistKey)
        .map { it.toLightSong() }
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .map { (_, tracks) ->
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            AlbumItem(
                name = sorted.firstOrNull()?.album,
                artist = sorted.firstOrNull()?.effectiveAlbumArtist,
                trackCount = sorted.size,
                songs = sorted.take(1) // seed only for art
            )
        }
        .sortedWith(
            compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
}

private fun CatalogTrackEntity.toLightSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    durationMs = durationMs,
    contentUri = android.net.Uri.parse(contentUri),
    albumArtUri = albumArtUri?.let { android.net.Uri.parse(it) },
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    path = path,
    mimeType = mimeType
)
