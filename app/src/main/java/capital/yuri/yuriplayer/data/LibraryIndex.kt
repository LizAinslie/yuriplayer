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
                    val songs = repository.scanLibrary()
                    cache.save(songs)
                    songs
                }
                _songs.value = scanned
                _lastScannedAt.value = System.currentTimeMillis()
                Log.d(TAG, "Refresh: ${scanned.size} songs → ${cache.cacheFilePath()}")
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
        return base.filter { song ->
            song.title.contains(q, ignoreCase = true) ||
                song.artist.contains(q, ignoreCase = true) ||
                song.albumArtist.contains(q, ignoreCase = true) ||
                song.album.contains(q, ignoreCase = true)
        }
    }

    /** Group by album title + album artist (not track artist) so features do not split albums. */
    fun albums(query: String = "", taggedOnly: Boolean = true): List<AlbumItem> {
        val q = query.trim()
        val source = if (taggedOnly) _songs.value.filter { it.isTagged } else _songs.value
        return source
            .groupBy { it.album to it.effectiveAlbumArtist }
            .map { (key, tracks) ->
                AlbumItem(
                    name = key.first,
                    artist = key.second,
                    trackCount = tracks.size,
                    songs = tracks.sortedWith(
                        compareBy<Song> { it.trackNumber }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    )
                )
            }
            .filter {
                q.isEmpty() ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
    }

    fun artists(query: String = "", taggedOnly: Boolean = true): List<ArtistItem> {
        val q = query.trim()
        val source = if (taggedOnly) _songs.value.filter { it.isTagged } else _songs.value
        return source
            .groupBy { it.effectiveAlbumArtist }
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

    fun taggedCount(): Int = _songs.value.count { it.isTagged }
    fun untaggedCount(): Int = _songs.value.count { !it.isTagged }

    companion object {
        private const val TAG = "LibraryIndex"
        const val DEFAULT_STALE_MS = 12L * 60 * 60 * 1000

        fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> {
            // Tagged first, then by requested mode
            val taggedFirst = compareBy<Song> { !it.isTagged }
            return when (mode) {
                SortMode.TITLE -> songs.sortedWith(
                    taggedFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.ARTIST -> songs.sortedWith(
                    taggedFirst
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.effectiveAlbumArtist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.ALBUM -> songs.sortedWith(
                    taggedFirst
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.effectiveAlbumArtist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SortMode.TRACK -> songs.sortedWith(
                    taggedFirst
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
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
