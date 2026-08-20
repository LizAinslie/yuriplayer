package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast discography for the artist page.
 *
 * Full expand only happens when an album is opened. Rows from the catalog can
 * still fragment into multiple albumKeys for the same release — group by
 * [albumKey] so the LazyRow never sees two cards with the same name|artist|year.
 */
suspend fun lightAlbumItemsForArtist(
    dao: CatalogDao,
    artistKey: String
): List<AlbumItem> = withContext(Dispatchers.IO) {
    if (artistKey.isBlank()) return@withContext emptyList()

    val rows = dao.getAlbumsForArtist(artistKey)
    if (rows.isNotEmpty()) {
        val items = ArrayList<AlbumItem>(rows.size)
        val seen = LinkedHashSet<String>()
        // Prefer highest trackCount per logical release
        val grouped = rows.groupBy { albumKey(it.name, it.artist) }
        for ((_, group) in grouped) {
            val row = group.maxByOrNull { it.trackCount } ?: group.first()
            val mergeKey = albumKey(row.name, row.artist)
            if (!seen.add(mergeKey)) continue

            var seed: Song? = null
            for (candidate in listOf(row) + group) {
                val track = dao.getOneTrackForAlbum(candidate.albumKey) ?: continue
                seed = track.toLightSong()
                break
            }

            items += AlbumItem(
                name = row.name,
                artist = row.artist,
                trackCount = group.maxOf { it.trackCount },
                songs = listOfNotNull(seed)
            )
        }
        return@withContext items.sortedWith(
            compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }

    dao.getTracksForArtist(artistKey)
        .map { it.toLightSong() }
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .map { (_, tracks) ->
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            AlbumItem(
                name = sorted.firstOrNull()?.album,
                artist = sorted.firstOrNull()?.effectiveAlbumArtist,
                trackCount = sorted.size,
                songs = sorted.take(1)
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
