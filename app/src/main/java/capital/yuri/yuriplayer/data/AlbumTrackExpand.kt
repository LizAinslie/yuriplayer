package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity

/**
 * Resolve every catalog row for a release.
 *
 * Local + Jellyfin often store *different* albumKey strings for the same album
 * (artist spelling, albumArtist vs artist). Explore song search still finds them
 * by title; album pages used to only query one albumKey and showed a single track.
 *
 * Strategy:
 *  1. rows for the exact albumKey
 *  2. all rows with the same album tag (any source / key)
 *  3. folded albumKey variant
 *  4. soft LIKE broaden
 * then [CatalogRepository.dedupeLogicalTracks] (local preferred).
 */
suspend fun expandAlbumTracks(
    dao: CatalogDao,
    albumKey: String
): List<Song> {
    if (albumKey.isBlank()) return emptyList()

    val direct = dao.getTracksForAlbum(albumKey)
    val row = dao.getAlbum(albumKey)
    val albumName = row?.name
        ?: direct.firstOrNull()?.album
        ?: albumKey.substringAfter('|', missingDelimiterValue = "").takeIf { it.isNotBlank() }
    val artistName = row?.artist
        ?: direct.firstOrNull()?.let { it.albumArtist ?: it.artist }
        ?: albumKey.substringBefore('|').takeIf { it.isNotBlank() }

    val candidates = LinkedHashMap<String, CatalogTrackEntity>()
    direct.forEach { candidates[it.songKey] = it }

    if (!albumName.isNullOrBlank()) {
        // Primary Clancy fix: exact album tag, ignore fragmented albumKey
        dao.getTracksByAlbumName(albumName.trim(), limit = 500).forEach { t ->
            candidates.putIfAbsent(t.songKey, t)
        }

        val artistFolded = TrackIdentity.normalizeToken(artistName)
        // Only filter by artist when the title match set is huge (ambiguous titles)
        if (artistFolded.isNotEmpty() && candidates.size > 60) {
            val filtered = candidates.values.filter { t ->
                val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
                aa.isEmpty() || aa == artistFolded ||
                    TrackIdentity.normalizeToken(t.artist) == artistFolded
            }
            if (filtered.size >= 2) {
                candidates.clear()
                filtered.forEach { candidates[it.songKey] = it }
            }
        }

        val needle = albumName.trim().take(64)
        if (needle.isNotEmpty()) {
            dao.searchTracks(needle, limit = 300).forEach { t ->
                if (candidates.size >= 500) return@forEach
                if (!TrackIdentity.albumsMatch(t.album, albumName)) return@forEach
                candidates.putIfAbsent(t.songKey, t)
            }
        }

        val folded = albumKey(albumName, artistName)
        if (folded != albumKey) {
            dao.getTracksForAlbum(folded).forEach { candidates.putIfAbsent(it.songKey, it) }
        }
    }

    return CatalogRepository.dedupeLogicalTracks(candidates.values.map { it.toSong() })
}

private fun CatalogTrackEntity.toSong(): Song = Song(
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
