package capital.yuri.yuriplayer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory view of the **persisted** catalog for UI.
 *
 * Continuous lists → StateFlows. Discrete scan moments → [events].
 * Heavy local rescans can be hosted by [LibraryScanService] for a live notification.
 */
class LibraryIndex(
    private val context: Context,
    private val repository: MusicRepository,
    private val cache: LibraryCache,
    private val catalog: CatalogRepository,
    private val notifier: LibraryScanNotifier
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

    private val _events = MutableSharedFlow<LibraryEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun bootstrap(staleAfterMs: Long = DEFAULT_STALE_MS) {
        scope.launch {
            val fromDb = withContext(Dispatchers.IO) { catalog.getAllSongs() }
            if (fromDb.isNotEmpty()) {
                _songs.value = fromDb
                _lastScannedAt.value = System.currentTimeMillis()
            } else {
                val cached = withContext(Dispatchers.IO) { cache.load() }
                if (cached != null && cached.songs.isNotEmpty()) {
                    _songs.value = cached.songs
                    _lastScannedAt.value = cached.scannedAt
                }
            }
            val age = System.currentTimeMillis() - _lastScannedAt.value
            if (_songs.value.isEmpty() || age > staleAfterMs) {
                refresh()
            }
        }
    }

    /** UI entry: prefer FGS so a long local scan shows a live notification. */
    fun refresh() {
        if (_isLoading.value) return
        LibraryScanService.startLocal(context.applicationContext)
    }

    /** Actual work — called from [LibraryScanService] or tests. */
    suspend fun refreshAndAwait() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        _events.tryEmit(LibraryEvent.ScanStarted())
        notifier.update("Scanning library", "Reading local files…")
        try {
            val songs = withContext(Dispatchers.IO) {
                catalog.syncLocalLibrary().also { cache.save(it) }
            }
            _songs.value = songs
            _lastScannedAt.value = System.currentTimeMillis()
            _events.tryEmit(LibraryEvent.ScanCompleted(songCount = songs.size))
            notifier.finish("Library scan", "${songs.size} tracks on this device")
        } catch (e: SecurityException) {
            _error.value = "Storage permission required"
            _events.tryEmit(LibraryEvent.ScanFailed("Storage permission required"))
            notifier.finish("Library scan", "Storage permission required")
        } catch (e: Exception) {
            _error.value = e.message ?: "Scan failed"
            Log.e(TAG, "Refresh failed", e)
            _events.tryEmit(LibraryEvent.ScanFailed(e.message ?: "Scan failed"))
            notifier.finish("Library scan", e.message ?: "Scan failed")
        } finally {
            _isLoading.value = false
        }
    }

    fun applyAlbumYear(albumKey: String, year: Int) {
        if (year !in 1000..2100) return
        scope.launch(Dispatchers.IO) {
            runCatching { catalog.applyAlbumYear(albumKey, year) }
        }
        val current = _songs.value
        var changed = false
        val next = current.map { song ->
            val key = albumKey(song.album, song.effectiveAlbumArtist)
            if (key == albumKey && (song.year == null || song.year <= 0)) {
                changed = true
                song.copy(year = year)
            } else song
        }
        if (changed) {
            _songs.value = next
            Log.i(TAG, "applied year $year to albumKey=$albumKey")
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

    fun albums(query: String = "", taggedOnly: Boolean = true): List<AlbumItem> {
        val q = query.trim()
        val source = if (taggedOnly) {
            _songs.value.filter { it.hasAlbum }
        } else {
            _songs.value
        }

        return source
            .groupBy { normalizeKey(it.album) }
            .mapNotNull { (albumKeyNorm, tracks) ->
                if (albumKeyNorm == null) return@mapNotNull null

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

                val displayName = tracks
                    .mapNotNull { it.album }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: tracks.firstOrNull()?.album

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
            .mapNotNull { (artistKeyNorm, tracks) ->
                if (artistKeyNorm == null) return@mapNotNull null
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
