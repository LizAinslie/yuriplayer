package capital.yuri.yuriplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory library backed by [LibraryCache].
 *
 * - [songs] is the full unsorted index (source of truth for search/sort)
 * - Sort and search never touch disk or MediaStore
 * - [refresh] rescans and rewrites the cache (manual or periodic)
 */
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

    /** Load cache immediately, then refresh in background if stale. */
    fun bootstrap(staleAfterMs: Long = DEFAULT_STALE_MS) {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached != null && cached.songs.isNotEmpty()) {
                _songs.value = cached.songs
                _lastScannedAt.value = cached.scannedAt
            }

            val age = System.currentTimeMillis() - (cached?.scannedAt ?: 0L)
            val needsScan = cached == null || cached.songs.isEmpty() || age > staleAfterMs
            if (needsScan) {
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
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sorted(mode: SortMode): List<Song> = sortSongs(_songs.value, mode)

    fun search(query: String, mode: SortMode = SortMode.TITLE): List<Song> {
        val q = query.trim()
        val base = sorted(mode)
        if (q.isEmpty()) return base
        return base.filter { song ->
            song.title.contains(q, ignoreCase = true) ||
                song.artist.contains(q, ignoreCase = true) ||
                song.album.contains(q, ignoreCase = true)
        }
    }

    fun albums(query: String = ""): List<AlbumItem> {
        val q = query.trim()
        return _songs.value
            .groupBy { it.album to it.artist }
            .map { (key, tracks) ->
                AlbumItem(
                    name = key.first,
                    artist = key.second,
                    trackCount = tracks.size,
                    songs = tracks.sortedBy { it.trackNumber }
                )
            }
            .filter {
                q.isEmpty() ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun artists(query: String = ""): List<ArtistItem> {
        val q = query.trim()
        return _songs.value
            .groupBy { it.artist }
            .map { (artist, tracks) ->
                ArtistItem(
                    name = artist,
                    trackCount = tracks.size,
                    albumCount = tracks.map { it.album }.distinct().size,
                    songs = tracks
                )
            }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    companion object {
        const val DEFAULT_STALE_MS = 12L * 60 * 60 * 1000 // 12 hours

        fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> {
            return when (mode) {
                SortMode.TITLE -> songs.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.ARTIST -> songs.sortedWith(
                    compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.ALBUM -> songs.sortedWith(
                    compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.TRACK -> songs.sortedWith(
                    compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
            }
        }
    }
}

data class AlbumItem(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val songs: List<Song>
)

data class ArtistItem(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val songs: List<Song>
)
