package capital.yuri.yuriplayer.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryIndex(
    private val repository: MusicRepository,
    private val cache: LibraryCache
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastScannedAt = MutableStateFlow(0L)
    val lastScannedAt: StateFlow<Long> = _lastScannedAt.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun bootstrap(staleAfterMs: Long = DEFAULT_STALE_MS) {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached != null && cached.songs.isNotEmpty()) {
                _songs.value = cached.songs
                _lastScannedAt.value = cached.scannedAt
            }
            val age = System.currentTimeMillis() - (cached?.scannedAt ?: 0L)
            if (cached == null || cached.songs.isEmpty() || age > staleAfterMs) {
                refresh()
            }
        }
    }

    fun refresh() {
        if (_isLoading.value) return
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val scanned = withContext(Dispatchers.IO) {
                    repository.scanLibrary().also { cache.save(it) }
                }
                _songs.value = scanned
                _lastScannedAt.value = System.currentTimeMillis()
            } catch (e: SecurityException) {
                _error.value = "Storage permission required"
            } catch (e: Exception) {
                _error.value = e.message ?: "Scan failed"
                Log.e(TAG, "Refresh failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sorted(mode: SortMode, taggedOnly: Boolean? = null): List<Song> {
        val base = when (taggedOnly) {
            true -> _songs.value.filter { it.isTagged }
            false -> _songs.value.filter { !it.isTagged }
            null -> _songs.value
        }
        return sortSongs(base, mode)
    }

    fun search(query: String, mode: SortMode = SortMode.TITLE, taggedOnly: Boolean? = null): List<Song> {
        val q = query.trim()
        val base = sorted(mode, taggedOnly)
        if (q.isEmpty()) return base
        return base.filter { songMatches(it, q) }
    }

    /**
     * One row per album title (normalized), not per track-artist combo.
     * Album artist = majority of explicit albumArtist tags, else majority of track artists.
     * That stops MILDRED-style feature tracks from splitting the album.
     */
    fun albums(query: String = "", taggedOnly: Boolean = true): List<AlbumItem> {
        val q = query.trim()
        val source = if (taggedOnly) {
            _songs.value.filter { it.hasAlbum }
        } else {
            _songs.value
        }

        return source
            .groupBy { normalizeKey(it.album) }
            .mapNotNull { (albumKey, tracks) ->
                if (albumKey == null) return@mapNotNull null

                // Prefer explicit albumArtist tags for the display artist
                val albumArtistVotes = tracks
                    .mapNotNull { it.albumArtist?.let { a -> normalizeKey(a) to a } }
                    .groupingBy { it.first }
                    .eachCount()
                val trackArtistVotes = tracks
                    .mapNotNull { it.artist?.let { a -> normalizeKey(a) to a } }
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

                // Canonical album title: most common original casing among tracks
                val displayName = tracks
                    .mapNotNull { it.album }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: tracks.firstOrNull()?.album

                // Dedupe tracks that appear twice (MediaStore + filesystem)
                val deduped = tracks.distinctBy {
                    it.path?.lowercase() ?: it.contentUri.toString()
                }

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

    fun artists(query: String = "", taggedOnly: Boolean = true): List<ArtistItem> {
        val q = query.trim()
        val source = if (taggedOnly) {
            _songs.value.filter { it.hasArtist }
        } else _songs.value

        return source
            .groupBy { normalizeKey(it.effectiveAlbumArtist) }
            .mapNotNull { (artistKey, tracks) ->
                if (artistKey == null) return@mapNotNull null
                val displayName = tracks
                    .mapNotNull { it.effectiveAlbumArtist }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val deduped = tracks.distinctBy {
                    it.path?.lowercase() ?: it.contentUri.toString()
                }
                val albumKeys = deduped.mapNotNull { normalizeKey(it.album) }.toSet()
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

    fun taggedCount(): Int = _songs.value.count { it.isTagged }
    fun untaggedCount(): Int = _songs.value.count { !it.isTagged }

    companion object {
        private const val TAG = "LibraryIndex"
        const val DEFAULT_STALE_MS = 12L * 60 * 60 * 1000

        /** Lowercase + collapse whitespace for stable grouping keys. */
        fun normalizeKey(value: String?): String? {
            if (value == null) return null
            val t = value.trim().replace(Regex("\\s+"), " ").lowercase()
            return t.takeIf { it.isNotEmpty() }
        }

        private fun songMatches(song: Song, q: String): Boolean {
            return song.displayTitle.contains(q, true) ||
                (song.artist?.contains(q, true) == true) ||
                (song.albumArtist?.contains(q, true) == true) ||
                (song.album?.contains(q, true) == true)
        }

        fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> {
            return when (mode) {
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
    }
}

data class AlbumItem(
    val name: String?,
    val artist: String?,
    val trackCount: Int,
    val songs: List<Song>
) {
    val displayName: String get() = name ?: "Unknown Album"
    val displayArtist: String get() = artist ?: "Unknown Artist"
}

data class ArtistItem(
    val name: String?,
    val trackCount: Int,
    val albumCount: Int,
    val songs: List<Song>
) {
    val displayName: String get() = name ?: "Unknown Artist"
}
