package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity

/**
 * Full release resolution for the album page.
 *
 * Strategy (in order):
 *  1. Every catalog row whose album tag equals the name (any albumKey / source)
 *  2. Every albumKey that shares that album name, then all rows under those keys
 *  3. Seed / navigation tracks
 *  4. Artist filter (strict when artist known — keeps "Let Them Talk" artists apart)
 *  5. Dedupe **sources of the same song** via disc+track#+title — never collapse
 *     different songs on the same album (Clancy = 14 rows, not 1).
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

    val bySongKey = LinkedHashMap<String, CatalogTrackEntity>()

    fun put(row: CatalogTrackEntity) {
        bySongKey.putIfAbsent(row.songKey, row)
    }

    seedRows.forEach { put(it) }

    if (!seedKey.isNullOrBlank()) {
        dao.getTracksForAlbum(seedKey).forEach { put(it) }
    }

    val name = albumName?.trim().orEmpty()
    val artist = artistName?.trim().orEmpty()

    if (name.isNotEmpty()) {
        // Primary: album tag + artist SQL
        dao.getTracksForAlbumNameArtist(name, artist, limit = 1500).forEach { put(it) }

        // Fallback: album tag only (artist filter in Kotlin)
        dao.getTracksByAlbumName(name, limit = 1500).forEach { put(it) }

        // Every albumKey that used this album title → pull full key groups
        val keys = dao.albumKeysForAlbumName(name).toMutableSet()
        if (!seedKey.isNullOrBlank()) keys += seedKey
        keys += albumKey(name, artistName)
        if (artist.isNotEmpty()) {
            // artistKey variants sometimes differ from albumArtist tag
            keys += albumKey(name, null)
        }
        for (k in keys) {
            if (k.isBlank()) continue
            dao.getTracksForAlbum(k).forEach { put(it) }
        }

        // Last resort: search (title/album LIKE)
        if (bySongKey.size < 3) {
            val needle = name.take(48)
            if (needle.length >= 2) {
                dao.searchTracks(needle, limit = 600).forEach { t ->
                    if (TrackIdentity.albumsMatch(t.album, name)) put(t)
                }
            }
        }
    }

    val artistFolded = TrackIdentity.normalizeToken(artistName)

    fun artistOk(t: CatalogTrackEntity): Boolean {
        if (artistFolded.isEmpty()) return true
        val aa = TrackIdentity.normalizeToken(t.albumArtist ?: t.artist)
        if (aa.isEmpty()) return true // untagged — keep if album matched
        if (aa == artistFolded) return true
        if (TrackIdentity.normalizeToken(t.artist) == artistFolded) return true
        // soft containment for "twenty one pilots" vs longer credits
        return aa.contains(artistFolded) || artistFolded.contains(aa)
    }

    fun albumOk(t: CatalogTrackEntity): Boolean {
        if (name.isEmpty()) return true
        if (TrackIdentity.albumsMatch(t.album, name)) return true
        if (!seedKey.isNullOrBlank() && t.albumKey == seedKey) return true
        return false
    }

    val filtered = bySongKey.values.filter { albumOk(it) && artistOk(it) }
    val fromDb = filtered.map { it.toSong() }
    val combined = fromDb + extraSeedSongs

    // Album-page dedupe: same disc+track#+normalized title = one logical song
    // (local + JF copies). Different track numbers stay different songs.
    val deduped = dedupeAlbumPageTracks(combined)

    Log.i(
        "AlbumExpand",
        "album='$name' artist='$artist' raw=${combined.size} deduped=${deduped.size} keys=${bySongKey.size}"
    )

    return deduped.sortedWith(
        compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
    )
}

/**
 * Collapse multi-source copies of the *same* song on one release.
 * Prefer disc|trackNumber|title when track # is present so Clancy's 14 tracks
 * never merge into one. Fall back to [TrackIdentity] when numbers are missing.
 */
fun dedupeAlbumPageTracks(tracks: List<Song>): List<Song> {
    if (tracks.isEmpty()) return emptyList()
    return tracks
        .groupBy { albumPageIdentity(it) }
        .values
        .map { group ->
            val preferred = group.minByOrNull {
                CatalogRepository.sourceTypeForSong(it).rank
            } ?: group.first()
            TrackIdentity.withRichestDisplay(preferred, group)
        }
}

private fun albumPageIdentity(song: Song): String {
    val disc = song.discNumber ?: 1
    val tn = song.trackNumber
    val title = TrackIdentity.normalizeTitle(song.title)
    return if (tn != null && tn > 0) {
        // Track number anchors identity on a release
        "n:$disc|$tn|$title"
    } else if (title.isNotEmpty()) {
        TrackIdentity.of(song)
    } else {
        // Untagged — never collapse distinct files
        "path:${song.songKey}"
    }
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
