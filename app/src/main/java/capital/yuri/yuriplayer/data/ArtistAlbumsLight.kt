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
    artistKey: String,
    displayName: String? = null
): List<AlbumItem> = withContext(Dispatchers.IO) {
    if (artistKey.isBlank()) return@withContext emptyList()
    val keys = ArtistAliasResolver.identityKeys(artistKey)

    val songs = LinkedHashMap<String, Song>()
    dao.getTracksForArtists(keys).forEach { songs[it.songKey] = it.toLightSong() }
    dao.getTracksByCreditRoles(keys, ArtistRole.PRIMARY.name).forEach {
        songs.putIfAbsent(it.songKey, it.toLightSong())
    }
    val canonical = ArtistAliasResolver.resolve(artistKey)
    val name = displayName ?: dao.getArtist(canonical)?.displayName
    val names = buildList {
        if (!name.isNullOrBlank()) add(name)
        dao.aliasesForCanonical(canonical).forEach { add(it.aliasName) }
    }.distinct()
    names.filter { it.length >= 3 }.forEach { n ->
        dao.getTracksMentioning(n).forEach { entity ->
            val song = entity.toLightSong()
            val hit = allCreditsForSong(song).any {
                it.role == ArtistRole.PRIMARY &&
                    ArtistAliasResolver.resolve(artistKey(it.name) ?: "") == canonical
            }
            if (hit) songs.putIfAbsent(entity.songKey, song)
        }
    }

    val fromTracks = songs.values
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .map { (key, tracks) ->
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            val albumName = sorted.firstOrNull()?.album
            AlbumLog.d(
                albumName,
                "light group key='$key' raw=${tracks.size} deduped=${sorted.size} " +
                    "titles=${sorted.joinToString { it.displayTitle }}"
            )
            if (sorted.size == 1 && tracks.size > 1) {
                AlbumLog.w(albumName, "light DEDUPE ${tracks.size} → 1 '${sorted.first().displayTitle}'")
                AlbumLog.songs(albumName, "light.raw", tracks)
            }
            AlbumItem(
                name = albumName,
                artist = primaryArtistName(sorted.firstOrNull()?.effectiveAlbumArtist)
                    ?: sorted.firstOrNull()?.effectiveAlbumArtist,
                trackCount = sorted.size,
                songs = sorted.take(1)
            )
        }

    val rows = dao.getAlbumsForArtists(keys)
    val fromRows = ArrayList<AlbumItem>()
    if (rows.isNotEmpty()) {
        val seen = LinkedHashSet<String>()
        val grouped = rows.groupBy { albumKey(it.name, it.artist) }
        for ((_, group) in grouped) {
            val row = group.maxByOrNull { it.trackCount } ?: group.first()
            val mergeKey = albumKey(row.name, row.artist)
            if (!seen.add(mergeKey)) continue
            if (isCombinedArtistName(row.artist)) continue
            var seed: Song? = null
            for (candidate in listOf(row) + group) {
                val track = dao.getOneTrackForAlbum(candidate.albumKey) ?: continue
                seed = track.toLightSong()
                break
            }
            fromRows += AlbumItem(
                name = row.name,
                artist = primaryArtistName(row.artist) ?: row.artist,
                trackCount = group.maxOf { it.trackCount },
                songs = listOfNotNull(seed)
            )
        }
    }

    val out = (fromTracks + fromRows)
        .groupBy { albumKey(it.name, it.artist) }
        .map { (key, group) ->
            val pick = group.maxByOrNull { it.trackCount } ?: group.first()
            if (group.size > 1) {
                AlbumLog.d(
                    pick.name,
                    "light merge cards=${group.size} key='$key' counts=${group.map { it.trackCount }} " +
                        "seeds=${group.map { it.songs.firstOrNull()?.displayTitle }}"
                )
            }
            pick
        }
        .sortedWith(
            compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    AlbumLog.i(
        displayName,
        "light discography n=${out.size} " +
            out.joinToString { "${it.displayName}×${it.trackCount}" }
    )
    return out
}

suspend fun lightAppearsOnForArtist(
    dao: CatalogDao,
    artistKey: String,
    displayName: String? = null
): List<AlbumItem> = withContext(Dispatchers.IO) {
    if (artistKey.isBlank()) return@withContext emptyList()
    val keys = ArtistAliasResolver.identityKeys(artistKey)
    val canonical = ArtistAliasResolver.resolve(artistKey)
    val songs = LinkedHashMap<String, Song>()
    dao.getTracksByCreditRoles(keys, ArtistRole.FEATURED.name).forEach {
        songs[it.songKey] = it.toLightSong()
    }
    val name = displayName ?: dao.getArtist(canonical)?.displayName
    val names = buildList {
        if (!name.isNullOrBlank()) add(name)
        dao.aliasesForCanonical(canonical).forEach { add(it.aliasName) }
    }.distinct()
    names.filter { it.length >= 3 }.forEach { n ->
        dao.getTracksMentioning(n).forEach { entity ->
            val song = entity.toLightSong()
            val credits = allCreditsForSong(song)
            val featured = credits.any {
                it.role == ArtistRole.FEATURED &&
                    ArtistAliasResolver.resolve(artistKey(it.name) ?: "") == canonical
            }
            val primary = credits.any {
                it.role == ArtistRole.PRIMARY &&
                    ArtistAliasResolver.resolve(artistKey(it.name) ?: "") == canonical
            }
            if (featured && !primary) songs.putIfAbsent(entity.songKey, song)
        }
    }
    if (songs.isEmpty()) return@withContext emptyList()

    songs.values
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .mapNotNull { (_, tracks) ->
            val primary = primaryArtistName(tracks.first().effectiveAlbumArtist)
                ?: tracks.first().effectiveAlbumArtist
            if (artistKey(primary) == artistKey) return@mapNotNull null
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            AlbumItem(
                name = sorted.firstOrNull()?.album,
                artist = primary,
                trackCount = sorted.size,
                songs = sorted.take(1)
            )
        }
        .groupBy { albumKey(it.name, it.artist) }
        .map { (_, group) -> group.maxByOrNull { it.trackCount } ?: group.first() }
        .sortedWith(
            compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
}

internal fun CatalogTrackEntity.toLightSong(): Song = Song(
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
