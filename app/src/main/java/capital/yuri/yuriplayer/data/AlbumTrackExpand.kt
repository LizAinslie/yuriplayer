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

/** Two SQL lookups, no search. Enough to paint the album page immediately. */
suspend fun fastExpandAlbumTracks(
    dao: CatalogDao,
    albumName: String?,
    artistName: String?,
    seedKey: String? = null,
    extraSeedSongs: List<Song> = emptyList()
): List<Song> {
    val name = albumName?.trim().orEmpty()
    val artist = artistName?.trim().orEmpty()
    if (name.isEmpty() && seedKey.isNullOrBlank() && extraSeedSongs.isEmpty()) return emptyList()

    val bySongKey = LinkedHashMap<String, CatalogTrackEntity>()
    fun put(row: CatalogTrackEntity) { bySongKey.putIfAbsent(row.songKey, row) }

    if (!seedKey.isNullOrBlank()) {
        dao.getTracksForAlbum(seedKey).forEach { put(it) }
    }
    if (name.isNotEmpty()) {
        dao.getTracksForAlbumNameArtist(name, artist, limit = 400).forEach { put(it) }
        if (bySongKey.size < 4) {
            dao.getTracksByAlbumName(name, limit = 400).forEach { put(it) }
        }
    }

    val artistFolded = foldTagToken(primaryArtistName(artistName) ?: artistName ?: "")
    val songs = bySongKey.values.filter { row ->
        val albumOk = name.isEmpty() ||
            TrackIdentity.albumsMatch(row.album, name) ||
            TrackIdentity.albumsNearlyMatch(row.album, name) ||
            (!seedKey.isNullOrBlank() && row.albumKey == seedKey)
        if (!albumOk) return@filter false
        if (artistFolded.isEmpty()) return@filter true
        val aa = foldTagToken(
            primaryArtistName(row.albumArtist ?: row.artist) ?: row.albumArtist ?: row.artist ?: ""
        )
        val ta = foldTagToken(primaryArtistName(row.artist) ?: row.artist ?: "")
        aa.isEmpty() || aa == artistFolded || ta == artistFolded ||
            aa.contains(artistFolded) || artistFolded.contains(aa)
    }.map { it.toSong() }

    val deduped = dedupeAlbumPageTracks(songs + extraSeedSongs)
    AlbumLog.i(name, "fast expand n=${deduped.size} raw=${bySongKey.size}")

    return deduped.sortedWith(albumTrackOrder())
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

    extraSeedSongs.take(4).forEach { seed ->
        val q = seed.title?.trim().orEmpty()
        if (q.length < 3) return@forEach
        dao.searchTracks(q.take(48), limit = 80).forEach { t ->
            if (!artistOk(t)) return@forEach
            val titleHit = TrackIdentity.titlesNearlyMatch(t.title, seed.title, minLen = 6)
            val albumHit = TrackIdentity.albumsNearlyMatch(t.album, name)
            if (!titleHit && !albumHit) return@forEach
            put(t)
            if (!t.albumKey.isNullOrBlank()) {
                dao.getTracksForAlbum(t.albumKey).forEach { put(it) }
            }
        }
    }

    val filtered = bySongKey.values.filter { albumOk(it) && artistOk(it) }
    val dropped = bySongKey.size - filtered.size
    if (dropped > 0) {
        AlbumLog.w(name, "filter dropped $dropped/${bySongKey.size} artistFold='$artistFolded'")

        bySongKey.values.filter { !albumOk(it) || !artistOk(it) }.forEach {
            AlbumLog.v(name, "  DROP albumOk=${albumOk(it)} artistOk=${artistOk(it)} ${AlbumLog.entityLine(it)}")

        }
    }
    AlbumLog.entities(name, "filtered", filtered)

    val fromDb = filtered.map { it.toSong() }
    val combined = fromDb + extraSeedSongs
    AlbumLog.songs(name, "extraSeed", extraSeedSongs)

    val groups = combined.groupBy { albumPageIdentity(it) }
    AlbumLog.identityGroups(name, groups)

    val deduped = dedupeAlbumPageTracks(combined)

    AlbumLog.i(name, "expand done artist='$artist' raw=${combined.size} deduped=${deduped.size} keys=${bySongKey.size} filtered=${filtered.size}")

    AlbumLog.songs(name, "deduped", deduped)

    return deduped.sortedWith(albumTrackOrder())
}

/**
 * Collapse multi-source copies of the *same* song on one release.
 * Prefer disc|trackNumber|title when track # is present so Clancy's 14 tracks
 * never merge into one. Fall back to [TrackIdentity] when numbers are missing.
 */
fun dedupeAlbumPageTracks(tracks: List<Song>): List<Song> {
    if (tracks.isEmpty()) return emptyList()
    val unique = tracks.distinctBy { it.songKey }
    val groups = collapseNearTitleGroups(unique.groupBy { albumPageIdentity(it) })
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
        .sortedWith(albumTrackOrder())
}

fun albumTrackOrder(): Comparator<Song> =
    compareBy<Song> { it.discNumber ?: 1 }
        .thenBy { it.trackNumber ?: filenameTrackHint(it) }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }

private fun filenameTrackHint(song: Song): Int {
    val parsed = FilenameMetadataParser.parse(song.path ?: song.contentUri.toString())
    return parsed.trackNumber ?: Int.MAX_VALUE
}

private fun collapseNearTitleGroups(
    groups: Map<String, List<Song>>
): Map<String, List<Song>> {
    if (groups.size <= 1) return groups
    val entries = groups.entries.toList()
    val used = BooleanArray(entries.size)
    val out = LinkedHashMap<String, List<Song>>()
    for (i in entries.indices) {
        if (used[i]) continue
        used[i] = true
        val bucket = entries[i].value.toMutableList()
        val sample = bucket.first()
        val disc = sample.discNumber ?: 1
        val tn = sample.trackNumber
        for (j in i + 1 until entries.size) {
            if (used[j]) continue
            val other = entries[j].value.first()
            val sameSlot = tn != null && tn > 0 &&
                other.trackNumber == tn &&
                (other.discNumber ?: 1) == disc
            val minLen = if (sameSlot) 6 else 16
            if (!TrackIdentity.titlesNearlyMatch(sample.title, other.title, minLen = minLen)) {
                continue
            }
            if (!sameSlot && tn != null && other.trackNumber != null && other.trackNumber != tn) {
                continue
            }
            used[j] = true
            bucket += entries[j].value
        }
        out[entries[i].key] = bucket
    }
    return out
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
