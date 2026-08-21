package capital.yuri.yuriplayer.data

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

    AlbumLog.i(albumName, "expand key='$albumKey' artist='$artistName' direct=${direct.size} row=${row != null}")
    AlbumLog.entities(albumName, "direct", direct)

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
    val name = albumName?.trim().orEmpty()
    val artist = artistName?.trim().orEmpty()

    fun put(row: CatalogTrackEntity) {
        bySongKey.putIfAbsent(row.songKey, row)
    }

    seedRows.forEach { put(it) }
    AlbumLog.step(name.ifEmpty { albumName }, "seedRows", seedRows.size)

    if (!seedKey.isNullOrBlank()) {
        val keyed = dao.getTracksForAlbum(seedKey)
        keyed.forEach { put(it) }
        AlbumLog.step(name.ifEmpty { albumName }, "seedKey", keyed.size, "key=$seedKey")
    }

    if (name.isNotEmpty()) {
        // Primary: album tag + artist SQL
        dao.getTracksForAlbumNameArtist(name, artist, limit = 1500).forEach { put(it) }
        AlbumLog.step(name, "name+artist SQL", bySongKey.size, "artist='$artist'")

        dao.getTracksByAlbumName(name, limit = 1500).forEach { put(it) }
        AlbumLog.step(name, "name-only SQL", bySongKey.size)

        val keys = dao.albumKeysForAlbumName(name).toMutableSet()
        if (!seedKey.isNullOrBlank()) keys += seedKey
        keys += albumKey(name, artistName)
        if (artist.isNotEmpty()) {
            keys += albumKey(name, null)
        }
        AlbumLog.d(name, "albumKeys=${keys.size} ${keys.joinToString()}")
        for (k in keys) {
            if (k.isBlank()) continue
            val rows = dao.getTracksForAlbum(k)
            rows.forEach { put(it) }
            AlbumLog.v(name, "  key '$k' → ${rows.size}")
        }
        AlbumLog.step(name, "after keys", bySongKey.size)

        if (name.length >= 16) {
            val needle = name.take(28)
            val hits = dao.searchTracks(needle, limit = 400)
            var added = 0
            hits.forEach { t ->
                if (TrackIdentity.albumsNearlyMatch(t.album, name)) {
                    val before = bySongKey.size
                    put(t)
                    if (bySongKey.size > before) added++
                }
            }
            AlbumLog.step(name, "near-name search", bySongKey.size, "hits=${hits.size} added=$added")
        }

        if (bySongKey.size < 3) {
            val needle = name.take(48)
            if (needle.length >= 2) {
                val hits = dao.searchTracks(needle, limit = 600)
                var added = 0
                hits.forEach { t ->
                    if (TrackIdentity.albumsMatch(t.album, name)) {
                        val before = bySongKey.size
                        put(t)
                        if (bySongKey.size > before) added++
                    }
                }
                AlbumLog.step(name, "search fallback", bySongKey.size, "hits=${hits.size} added=$added")
            }
        }
    }

    val artistFolded = foldTagToken(primaryArtistName(artistName) ?: artistName ?: "")

    fun artistOk(t: CatalogTrackEntity): Boolean {
        if (artistFolded.isEmpty()) return true
        val aa = foldTagToken(
            primaryArtistName(t.albumArtist ?: t.artist) ?: t.albumArtist ?: t.artist ?: ""
        )
        val ta = foldTagToken(primaryArtistName(t.artist) ?: t.artist ?: "")
        if (aa.isEmpty() && ta.isEmpty()) return true
        if (aa == artistFolded || ta == artistFolded) return true
        return aa.contains(artistFolded) || artistFolded.contains(aa) ||
            ta.contains(artistFolded) || artistFolded.contains(ta)
    }

    fun albumOk(t: CatalogTrackEntity): Boolean {
        if (name.isEmpty()) return true
        if (TrackIdentity.albumsMatch(t.album, name)) return true
        if (TrackIdentity.albumsNearlyMatch(t.album, name)) return true
        if (!seedKey.isNullOrBlank() && t.albumKey == seedKey) return true
        return false
    }

    val filtered = bySongKey.values.filter { albumOk(it) && artistOk(it) }
    val dropped = bySongKey.size - filtered.size
    if (dropped > 0) {
        AlbumLog.w(name, "filter dropped $dropped/${bySongKey.size} artistFold='$artistFolded'")
        bySongKey.values.filter { !albumOk(it) || !artistOk(it) }.forEach {
            AlbumLog.v(
                name,
                "  DROP albumOk=${albumOk(it)} artistOk=${artistOk(it)} ${AlbumLog.entityLine(it)}"
            )
        }
    }
    AlbumLog.entities(name, "filtered", filtered)

    val fromDb = filtered.map { it.toSong() }
    val combined = fromDb + extraSeedSongs
    AlbumLog.songs(name, "extraSeed", extraSeedSongs)

    val groups = combined.groupBy { albumPageIdentity(it) }
    AlbumLog.identityGroups(name, groups)

    val deduped = dedupeAlbumPageTracks(combined)

    AlbumLog.i(
        name,
        "expand done artist='$artist' raw=${combined.size} deduped=${deduped.size} keys=${bySongKey.size} filtered=${filtered.size}"
    )
    AlbumLog.songs(name, "deduped", deduped)

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
    val unique = tracks.distinctBy { it.songKey }
    val groups = unique.groupBy { albumPageIdentity(it) }
    val albumHint = tracks.firstOrNull()?.album
    if (groups.any { it.value.size > 1 } || groups.size != tracks.size) {
        AlbumLog.identityGroups(albumHint, groups)
    }
    return groups.values
        .map { group ->
            val preferred = group.minByOrNull {
                CatalogRepository.sourceTypeForSong(it).rank
            } ?: group.first()
            TrackIdentity.withRichestDisplay(preferred, group)
        }
}

internal fun albumPageIdentity(song: Song): String {
    val disc = song.discNumber ?: 1
    val tn = song.trackNumber
    val title = TrackIdentity.normalizeTitle(song.title)
    // Always include title so missing/duplicate track numbers cannot collapse
    // a whole release onto one row (Clancy / The Craving).
    return if (tn != null && tn > 0 && title.isNotEmpty()) {
        "n:$disc|$tn|$title"
    } else if (title.isNotEmpty()) {
        TrackIdentity.of(song)
    } else {
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
