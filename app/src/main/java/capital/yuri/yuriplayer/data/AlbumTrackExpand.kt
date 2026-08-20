package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity

/**
 * Resolve catalog rows for one release.
 *
 * Album *name* alone is not enough — "Let Them Talk" (A2D Sound) must not merge
 * with Hugh Laurie's LP of the same title. When [artistName] is known we **always**
 * require a matching albumArtist/artist (folded). Empty artist tags on a row are
 * only kept if the row already shares the seed albumKey.
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

    val artistFolded = TrackIdentity.normalizeToken(artistName)
    val seedKeys = candidates.keys.toHashSet()

    fun artistOk(t: CatalogTrackEntity): Boolean {
        if (artistFolded.isEmpty()) return true
        // Already on the exact seed key → keep (same release fragment)
        if (t.songKey in seedKeys) return true
        if (!seedKey.isNullOrBlank() && t.albumKey == seedKey) return true
        val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
        if (aa.isEmpty()) return false // unknown artist, different key → drop
        return aa == artistFolded || TrackIdentity.normalizeToken(t.artist) == artistFolded
    }

    if (!albumName.isNullOrBlank()) {
        dao.getTracksByAlbumName(albumName.trim(), limit = 500).forEach { t ->
            if (artistOk(t)) candidates.putIfAbsent(t.songKey, t)
        }

        val needle = albumName.trim().take(64)
        if (needle.isNotEmpty()) {
            dao.searchTracks(needle, limit = 400).forEach { t ->
                if (candidates.size >= 600) return@forEach
                if (!TrackIdentity.albumsMatch(t.album, albumName) &&
                    !t.album.equals(albumName, ignoreCase = true)
                ) return@forEach
                if (!artistOk(t)) return@forEach
                candidates.putIfAbsent(t.songKey, t)
            }
        }

        val folded = albumKey(albumName, artistName)
        if (folded != seedKey) {
            dao.getTracksForAlbum(folded).forEach { t ->
                if (artistOk(t)) candidates.putIfAbsent(t.songKey, t)
            }
        }
    }

    // Final hard filter — never keep a foreign artist once we know ours
    val filtered = if (artistFolded.isEmpty()) {
        candidates.values
    } else {
        candidates.values.filter { t ->
            if (t.songKey in seedKeys || (!seedKey.isNullOrBlank() && t.albumKey == seedKey)) {
                true
            } else {
                val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
                aa == artistFolded || TrackIdentity.normalizeToken(t.artist) == artistFolded
            }
        }
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
