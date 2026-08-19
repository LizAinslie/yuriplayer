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
 *  3. LIKE search on album name + soft artist filter
 *  4. folded albumKey variant
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

    return expandAlbumTracksByName(dao, albumName, artistName, seedKey = albumKey, seedRows = direct)
}

/**
 * Name-first expand used by album pages when the navigation seed only has one track
 * but the header trackCount says otherwise.
 */
suspend fun expandAlbumTracksByName(
    dao: CatalogDao,
    albumName: String?,
    artistName: String?,
    seedKey: String? = null,
    seedRows: List<CatalogTrackEntity> = emptyList()
): List<Song> {
    val candidates = LinkedHashMap<String, CatalogTrackEntity>()
    seedRows.forEach { candidates[it.songKey] = it }

    if (!seedKey.isNullOrBlank()) {
        dao.getTracksForAlbum(seedKey).forEach { candidates.putIfAbsent(it.songKey, it) }
    }

    if (!albumName.isNullOrBlank()) {
        // Exact album tag across every source/key
        dao.getTracksByAlbumName(albumName.trim(), limit = 500).forEach { t ->
            candidates.putIfAbsent(t.songKey, t)
        }

        // LIKE broaden — catches trailing spaces / slight variants
        val needle = albumName.trim().take(64)
        if (needle.isNotEmpty()) {
            dao.searchTracks(needle, limit = 400).forEach { t ->
                if (candidates.size >= 600) return@forEach
                if (!TrackIdentity.albumsMatch(t.album, albumName) &&
                    !t.album.equals(albumName, ignoreCase = true)
                ) return@forEach
                candidates.putIfAbsent(t.songKey, t)
            }
        }

        val folded = albumKey(albumName, artistName)
        if (folded != seedKey) {
            dao.getTracksForAlbum(folded).forEach { candidates.putIfAbsent(it.songKey, it) }
        }
    }

    // Soft artist filter only when the set is huge (ambiguous album titles)
    val artistFolded = TrackIdentity.normalizeToken(artistName)
    val filtered = if (artistFolded.isNotEmpty() && candidates.size > 80) {
        val match = candidates.values.filter { t ->
            val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
            aa.isEmpty() || aa == artistFolded ||
                TrackIdentity.normalizeToken(t.artist) == artistFolded
        }
        if (match.size >= 2) match else candidates.values
    } else {
        candidates.values
    }

    return CatalogRepository.dedupeLogicalTracks(filtered.map { it.toSong() })
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
