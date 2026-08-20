package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity

/**
 * Resolve every catalog row for one release, then [CatalogRepository.dedupeLogicalTracks]
 * so local + Jellyfin copies of the *same* song collapse to one row with multi-source.
 *
 * Different songs on the same album MUST stay distinct (Clancy = 14 tracks, not 1).
 * Different artists with the same album title MUST stay separate (Let Them Talk).
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

    return expandAlbumTracksByName(
        dao = dao,
        albumName = albumName,
        artistName = artistName,
        seedKey = albumKey,
        seedRows = direct
    )
}

suspend fun expandAlbumTracksByName(
    dao: CatalogDao,
    albumName: String?,
    artistName: String?,
    seedKey: String? = null,
    seedRows: List<CatalogTrackEntity> = emptyList(),
    extraSeedSongs: List<Song> = emptyList()
): List<Song> {
    if (albumName.isNullOrBlank() && seedKey.isNullOrBlank() && seedRows.isEmpty() && extraSeedSongs.isEmpty()) {
        return emptyList()
    }

    val candidates = LinkedHashMap<String, CatalogTrackEntity>()
    seedRows.forEach { candidates[it.songKey] = it }

    if (!seedKey.isNullOrBlank()) {
        dao.getTracksForAlbum(seedKey).forEach { candidates.putIfAbsent(it.songKey, it) }
    }

    val artistFolded = TrackIdentity.normalizeToken(artistName)
    val albumFolded = TrackIdentity.normalizeToken(albumName)

    fun artistCompatible(t: CatalogTrackEntity): Boolean {
        if (artistFolded.isEmpty()) return true
        val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
        if (aa.isEmpty()) {
            // Untagged artist on a row that already shares our album name → keep
            return TrackIdentity.albumsMatch(t.album, albumName)
        }
        if (aa == artistFolded) return true
        if (TrackIdentity.normalizeToken(t.artist) == artistFolded) return true
        // Soft: artist string contains the other ("twenty one pilots" vs longer credits)
        return aa.contains(artistFolded) || artistFolded.contains(aa)
    }

    fun albumCompatible(t: CatalogTrackEntity): Boolean {
        if (albumFolded.isEmpty()) return true
        if (TrackIdentity.albumsMatch(t.album, albumName)) return true
        // Same folded key under a different albumKey string
        val rowKey = t.albumKey
        if (!rowKey.isNullOrBlank() && !seedKey.isNullOrBlank()) {
            // allow exact key match always
            if (rowKey == seedKey) return true
        }
        return false
    }

    if (!albumName.isNullOrBlank()) {
        // Primary: every row with this album tag
        dao.getTracksByAlbumName(albumName.trim(), limit = 800).forEach { t ->
            if (albumCompatible(t) && artistCompatible(t)) {
                candidates.putIfAbsent(t.songKey, t)
            }
        }

        // Secondary: search (catches slight album title variants in title/album fields)
        val needle = albumName.trim().take(48)
        if (needle.length >= 2) {
            dao.searchTracks(needle, limit = 500).forEach { t ->
                if (candidates.size >= 900) return@forEach
                if (!albumCompatible(t)) return@forEach
                if (!artistCompatible(t)) return@forEach
                candidates.putIfAbsent(t.songKey, t)
            }
        }

        // Folded key lookup (artist|album)
        val folded = albumKey(albumName, artistName)
        if (folded != seedKey) {
            dao.getTracksForAlbum(folded).forEach { t ->
                if (artistCompatible(t)) candidates.putIfAbsent(t.songKey, t)
            }
        }
    }

    val fromDb = candidates.values.map { it.toSong() }
    // Dedupe sources only — same logical track across providers becomes one row
    val merged = CatalogRepository.dedupeLogicalTracks(fromDb + extraSeedSongs)

    // Sort for stable discography / album page order
    return merged.sortedWith(
        compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
    )
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
