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
        return base.filter { song ->
            song.displayTitle.contains(q, true) ||
                (song.artist?.contains(q, true) == true) ||
                (song.albumArtist?.contains(q, true) == true) ||
                (song.album?.contains(q, true) == true)
        }
    }

    fun albums(query: String = "", taggedOnly: Boolean = true): List<AlbumItem> {
        val q = query.trim()
        val source = if (taggedOnly) {
            _songs.value.filter { it.hasAlbum }
        } else {
            _songs.value
        }
        return source
            .groupBy { (it.album ?: "") to (it.effectiveAlbumArtist ?: "") }
            .map { (key, tracks) ->
                AlbumItem(
                    name = key.first.ifBlank { null },
                    artist = key.second.ifBlank { null },
                    trackCount = tracks.size,
                    songs = tracks.sortedWith(
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
            .groupBy { it.effectiveAlbumArtist }
            .map { (artist, tracks) ->
                ArtistItem(
                    name = artist,
                    trackCount = tracks.size,
                    albumCount = tracks.mapNotNull { it.album }.distinct().size,
                    songs = tracks
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

        /**
         * Items missing the sort-key field are pushed to the bottom
         * ("untagged for this sort").
         */
        fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> {
            return when (mode) {
                SortMode.TITLE -> songs.sortedWith(
                    compareBy<Song> { !it.hasTitle } // no title tag → bottom
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
                    compareBy<Song> { !it.hasAlbum } // no album → bottom
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
