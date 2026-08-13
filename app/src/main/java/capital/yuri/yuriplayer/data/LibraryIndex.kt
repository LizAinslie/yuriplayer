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

/**
 * Library state:
 * 1. Disk: [LibraryCache] in app cacheDir (survives process death)
 * 2. Memory: [songs] StateFlow — sort/search/group use this only
 * 3. Refresh: rescans device, then overwrites disk + memory
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

    /** Load cache from disk immediately, then refresh if missing or stale. */
    fun bootstrap(staleAfterMs: Long = DEFAULT_STALE_MS) {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached != null && cached.songs.isNotEmpty()) {
                _songs.value = cached.songs
                _lastScannedAt.value = cached.scannedAt
                Log.d(TAG, "Bootstrap: ${cached.songs.size} songs from disk cache")
            }

            val age = System.currentTimeMillis() - (cached?.scannedAt ?: 0L)
            val needsScan = cached == null || cached.songs.isEmpty() || age > staleAfterMs
            if (needsScan) {
                Log.d(TAG, "Bootstrap: cache missing/stale (age=${age}ms) → refresh")
                refresh()
            }
        }
    }

    /** Full device scan, then write to cacheDir and update memory. */
    fun refresh() {
        if (_isLoading.value) return
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val scanned = withContext(Dispatchers.IO) {
                    val songs = repository.scanLibrary()
                    cache.save(songs)
                    songs
                }
                _songs.value = scanned
                _lastScannedAt.value = System.currentTimeMillis()
                Log.d(TAG, "Refresh complete: ${scanned.size} songs cached at ${cache.cacheFilePath()}")
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

    /** In-memory only — does not read/write disk. */
    fun sorted(mode: SortMode): List<Song> = sortSongs(_songs.value, mode)

    /** In-memory only. */
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

    /** In-memory only. */
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

    /** In-memory only. */
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
        private const val TAG = "LibraryIndex"
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
