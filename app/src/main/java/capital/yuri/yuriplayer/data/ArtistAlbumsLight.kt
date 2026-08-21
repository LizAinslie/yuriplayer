package capital.yuri.yuriplayer.data

import android.net.Uri
import capital.yuri.yuriplayer.data.db.CatalogAlbumEntity
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fast discography for the artist page.
 *
 * Prefer [catalog_albums] + one seed track per release. Full track scans and
 * LIKE-mention queries are a fallback when the album table is empty.
 */
suspend fun lightAlbumItemsForArtist(
    dao: CatalogDao,
    artistKey: String,
    displayName: String? = null
): List<AlbumItem> = withContext(Dispatchers.IO) {
    if (artistKey.isBlank()) return@withContext emptyList()
    val keys = ArtistAliasResolver.identityKeys(artistKey)
    val rows = dao.getAlbumsForArtists(keys)
        .filter { !isCombinedArtistName(it.artist) }
    if (rows.isNotEmpty()) {
        val seeds = rows.map { it.albumKey }.chunked(400).flatMap { chunk ->
            if (chunk.isEmpty()) emptyList() else dao.oneTrackPerAlbum(chunk)
        }.associateBy { it.albumKey }
        val items = rows.map { row ->
            row.toLightAlbum(seeds[row.albumKey]?.toLightSong())
        }
        AlbumLog.i(
            displayName,
            "light discography from album rows n=${items.size} keys=${keys.size}"
        )
        return@withContext clusterNearDuplicateAlbums(items).sortedWith(lightAlbumOrder())
    }

    val songs = LinkedHashMap<String, Song>()
    dao.getTracksForArtists(keys).forEach { songs[it.songKey] = it.toLightSong() }
    dao.getTracksByCreditRoles(keys, ArtistRole.PRIMARY.name).forEach {
        songs.putIfAbsent(it.songKey, it.toLightSong())
    }

    val fromTracks = songs.values
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .map { (key, tracks) ->
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            AlbumItem(
                name = sorted.firstOrNull()?.album,
                artist = primaryArtistName(sorted.firstOrNull()?.effectiveAlbumArtist)
                    ?: sorted.firstOrNull()?.effectiveAlbumArtist,
                trackCount = sorted.size,
                songs = sorted.take(1)
            )
        }
    AlbumLog.i(displayName, "light discography from tracks n=${fromTracks.size}")
    clusterNearDuplicateAlbums(fromTracks).sortedWith(lightAlbumOrder())
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
    if (songs.isEmpty()) return@withContext emptyList()

    songs.values
        .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
        .mapNotNull { (_, tracks) ->
            val primary = primaryArtistName(tracks.first().effectiveAlbumArtist)
                ?: tracks.first().effectiveAlbumArtist
            if (artistKey(primary) == canonical) return@mapNotNull null
            val sorted = CatalogRepository.dedupeLogicalTracks(tracks)
            AlbumItem(
                name = sorted.firstOrNull()?.album,
                artist = primary,
                trackCount = sorted.size,
                songs = sorted.take(1)
            )
        }
        .let { clusterNearDuplicateAlbums(it) }
        .sortedWith(lightAlbumOrder())
}

internal fun CatalogAlbumEntity.toLightAlbum(seed: Song?): AlbumItem {
    val art = seed?.albumArtUri
        ?: coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        ?: coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isFile) Uri.fromFile(f) else runCatching { Uri.parse(path) }.getOrNull()
        }
    val song = when {
        seed != null && seed.albumArtUri == null && art != null -> seed.copy(albumArtUri = art)
        seed != null -> seed
        else -> Song(
            id = albumKey.hashCode().toLong(),
            title = name,
            artist = artist,
            albumArtist = artist,
            album = name,
            durationMs = null,
            contentUri = Uri.EMPTY,
            albumArtUri = art,
            year = year,
            path = "album:$albumKey"
        )
    }.let { if (year != null && it.year == null) it.copy(year = year) else it }
    return AlbumItem(
        name = name,
        artist = artist,
        trackCount = trackCount,
        songs = listOf(song)
    )
}

private fun lightAlbumOrder() =
    compareByDescending<AlbumItem> { it.songs.firstOrNull()?.year ?: Int.MIN_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }

private fun clusterNearDuplicateAlbums(items: List<AlbumItem>): List<AlbumItem> {
    val exact = items.groupBy { albumKey(it.name, it.artist) }.map { (key, group) ->
        val pick = group.maxByOrNull { it.trackCount } ?: group.first()
        if (group.size > 1) {
            AlbumLog.d(
                pick.name,
                "light merge cards=${group.size} key='$key' counts=${group.map { it.trackCount }}"
            )
        }
        pick
    }
    val used = BooleanArray(exact.size)
    val out = ArrayList<AlbumItem>(exact.size)
    for (i in exact.indices) {
        if (used[i]) continue
        val a = exact[i]
        val members = mutableListOf(a)
        used[i] = true
        val artistA = artistKey(a.artist)
        for (j in i + 1 until exact.size) {
            if (used[j]) continue
            val b = exact[j]
            if (artistKey(b.artist) != artistA) continue
            if (!sameRelease(a, b)) continue
            used[j] = true
            members += b
        }
        val pick = members.maxByOrNull { it.trackCount } ?: a
        out += pick.copy(trackCount = members.maxOf { it.trackCount })
    }
    return out
}

private fun sameRelease(a: AlbumItem, b: AlbumItem): Boolean {
    if (!TrackIdentity.albumsNearlyMatch(a.name, b.name)) return false
    val sa = a.songs.firstOrNull()
    val sb = b.songs.firstOrNull()
    if (sa == null || sb == null) return true
    if (albumPageIdentity(sa) == albumPageIdentity(sb)) return true
    if (TrackIdentity.titlesNearlyMatch(sa.title, sb.title, minLen = 6)) return true
    val sameSlot = (sa.trackNumber ?: 0) > 0 &&
        sa.trackNumber == sb.trackNumber &&
        (sa.discNumber ?: 1) == (sb.discNumber ?: 1)
    return sameSlot && TrackIdentity.titlesNearlyMatch(sa.title, sb.title, minLen = 4)
}

internal fun CatalogTrackEntity.toLightSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    durationMs = durationMs,
    contentUri = Uri.parse(contentUri),
    albumArtUri = albumArtUri?.let { Uri.parse(it) },
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    path = path,
    mimeType = mimeType
)
