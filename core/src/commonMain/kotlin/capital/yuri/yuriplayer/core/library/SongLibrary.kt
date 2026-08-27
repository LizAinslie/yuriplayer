package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.isCombinedArtistName
import capital.yuri.yuriplayer.data.primaryArtistName

/**
 * Pure, platform-agnostic aggregation over a [List] of [Song]. This is the
 * same in-memory library-view logic Android's `LibraryIndex` uses, extracted
 * so desktop and future platforms share it without Context / Room / MediaStore.
 *
 * Everything here is deterministic: it reads a [Song] list and returns grouped
 * albums / artists / sorted / filtered lists. Persistence and scanning stay on
 * the platform side.
 */
object SongLibrary {

    fun normalizeKey(value: String?): String? {
        val t = value.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()
        return t.takeIf { it.isNotEmpty() }
    }

    fun albums(songs: List<Song>, query: String = "", taggedOnly: Boolean = true): List<AlbumItem> {
        val q = query.trim()
        val source = if (taggedOnly) songs.filter { it.hasAlbum } else songs

        return source
            .groupBy { normalizeKey(it.album) }
            .mapNotNull { (albumKeyNorm, tracks) ->
                if (albumKeyNorm == null) return@mapNotNull null

                val albumArtistVotes = tracks
                    .mapNotNull {
                        val raw = primaryArtistName(it.albumArtist) ?: it.albumArtist
                        raw?.let { a -> normalizeKey(a) to a }
                    }
                    .groupingBy { it.first }
                    .eachCount()
                val trackArtistVotes = tracks
                    .mapNotNull {
                        val raw = primaryArtistName(it.artist) ?: it.artist
                        raw?.let { a -> normalizeKey(a) to a }
                    }
                    .groupingBy { it.first }
                    .eachCount()

                val bestAlbumArtistKey = albumArtistVotes.maxByOrNull { it.value }?.key
                val bestTrackArtistKey = trackArtistVotes.maxByOrNull { it.value }?.key
                val displayArtist = when {
                    bestAlbumArtistKey != null ->
                        tracks.firstOrNull { normalizeKey(it.albumArtist) == bestAlbumArtistKey }?.albumArtist
                    bestTrackArtistKey != null ->
                        tracks.firstOrNull { normalizeKey(it.artist) == bestTrackArtistKey }?.artist
                    else -> null
                }

                val displayName = tracks
                    .mapNotNull { it.album }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: tracks.firstOrNull()?.album

                val deduped = tracks.distinctBy { it.path?.lowercase() ?: it.contentUri }

                AlbumItem(
                    name = displayName,
                    artist = displayArtist,
                    trackCount = deduped.size,
                    songs = deduped.sortedWith(
                        compareBy<Song> { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                    )
                )
            }
            .filter {
                q.isEmpty() ||
                    (it.name?.contains(q, true) == true) ||
                    (it.artist?.contains(q, true) == true)
            }
            .sortedWith(
                compareBy<AlbumItem> { it.artist == null }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist ?: "" }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" }
            )
    }

    fun artists(songs: List<Song>, query: String = "", taggedOnly: Boolean = true): List<ArtistItem> {
        val q = query.trim()
        val source = if (taggedOnly) songs.filter { it.hasArtist } else songs

        return source
            .groupBy { artistKey(it.effectiveAlbumArtist) }
            .mapNotNull { (key, tracks) ->
                if (key.isNullOrBlank()) return@mapNotNull null
                val displayName = tracks
                    .mapNotNull { primaryArtistName(it.effectiveAlbumArtist) ?: it.effectiveAlbumArtist }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                if (isCombinedArtistName(displayName)) return@mapNotNull null
                val deduped = tracks.distinctBy { it.path?.lowercase() ?: it.contentUri }
                val albumKeys = deduped.mapNotNull { albumKey(it.album, it.effectiveAlbumArtist) }.toSet()
                ArtistItem(
                    name = displayName,
                    trackCount = deduped.size,
                    albumCount = albumKeys.size,
                    songs = deduped
                )
            }
            .filter { q.isEmpty() || (it.name?.contains(q, true) == true) }
            .sortedWith(
                compareBy<ArtistItem> { it.name == null }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" }
            )
    }

    fun sorted(songs: List<Song>, mode: SortMode, taggedOnly: Boolean? = null): List<Song> {
        val base = when (taggedOnly) {
            true -> songs.filter { it.isTagged }
            false -> songs.filter { !it.isTagged }
            null -> songs
        }
        return sortSongs(base, mode)
    }

    fun search(songs: List<Song>, query: String, mode: SortMode = SortMode.TITLE, taggedOnly: Boolean? = null): List<Song> {
        val q = query.trim()
        val base = sorted(songs, mode, taggedOnly)
        if (q.isEmpty()) return base
        return base.filter { songMatches(it, q) }
    }

    private fun songMatches(song: Song, q: String): Boolean =
        song.displayTitle.contains(q, true) ||
            (song.artist?.contains(q, true) == true) ||
            (song.albumArtist?.contains(q, true) == true) ||
            (song.album?.contains(q, true) == true)

    fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> = when (mode) {
        SortMode.TITLE -> songs.sortedWith(
            compareBy<Song> { !it.hasTitle }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
        )
        SortMode.ARTIST -> songs.sortedWith(
            compareBy<Song> { !it.hasArtist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbumArtist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
        )
        SortMode.ALBUM -> songs.sortedWith(
            compareBy<Song> { !it.hasAlbum }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbumArtist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
        )
        SortMode.TRACK -> songs.sortedWith(
            compareBy<Song> { !it.hasAlbum }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayAlbum }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
        )
    }
}
