package capital.yuri.yuriplayer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory view of the **local** device library for My Stuff / browser UI.
 * Remote tracks live in [ExploreSearchService], not here — so cold start does
 * not load tens of thousands of Jellyfin rows onto the main thread.
 */
class LibraryIndex(
    private val context: Context,
    private val repository: MusicRepository,
    private val cache: LibraryCache,
    private val catalog: CatalogRepository,
    private val notifier: LibraryScanNotifier
) {

    // Default dispatcher: never block Main while loading large lists
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /**
     * Fast path for cold start:
     * 1. Disk cache only (instant) — enough for My Stuff UI
     * 2. Room local only if cache was empty
     * 3. Full MediaStore rescan only if still empty / stale, after long delays
     *    so MusicService restore + first Play own the CPU
     */
    fun bootstrap(staleAfterMs: Long = DEFAULT_STALE_MS) {
        scope.launch {
            // 1) Cache first — no Room, no MediaStore
            val cached = runCatching { cache.load() }.getOrNull()
            val hadCache = cached != null && cached.songs.isNotEmpty()
            if (hadCache) {
                _songs.value = cached!!.songs
                _lastScannedAt.value = cached.scannedAt
                Log.i(TAG, "bootstrap from cache: ${cached.songs.size} tracks")
            }

            // 2) Room only when cache missed — avoid loading large local tables
            //    while playback is still restoring.
            if (!hadCache) {
                val fromDb = runCatching { catalog.getLocalSongs() }.getOrDefault(emptyList())
                if (fromDb.isNotEmpty()) {
                    _songs.value = fromDb
                    _lastScannedAt.value = System.currentTimeMillis()
                    Log.i(TAG, "bootstrap from Room local: ${fromDb.size} tracks")
                }
            }

            val age = System.currentTimeMillis() - _lastScannedAt.value
            when {
                // Nothing at all — wait so playback restore finishes first
                _songs.value.isEmpty() -> {
                    delay(COLD_EMPTY_RESCAN_DELAY_MS)
                    if (_songs.value.isEmpty() && !_isLoading.value) {
                        Log.i(TAG, "bootstrap: empty after delay → local rescan")
                        refresh()
                    }
                }
                // Warm cache: optionally reconcile Room much later (no MediaStore)
                hadCache -> {
                    delay(ROOM_RECONCILE_DELAY_MS)
                    if (!_isLoading.value) {
                        runCatching { reloadFromCatalog() }
                        val stillStale = System.currentTimeMillis() - _lastScannedAt.value > staleAfterMs
                        if (stillStale && !_isLoading.value) {
                            delay(STALE_RESCAN_DELAY_MS)
                            if (!_isLoading.value) {
                                Log.i(TAG, "bootstrap: stale after reconcile → deferred local rescan")
                                refresh()
                            }
                        }
                    }
                }
                // Had Room rows but no cache — rescan later if stale
                age > staleAfterMs -> {
                    delay(STALE_RESCAN_DELAY_MS)
                    if (!_isLoading.value) {
                        Log.i(TAG, "bootstrap: stale (${age}ms) → deferred local rescan")
                        refresh()
                    }
                }
                else -> Log.i(TAG, "bootstrap: warm local index, skip auto-rescan")
            }
        }
    }

    /** Reload **local** Room tracks into the in-memory index (not remote). */
    suspend fun reloadFromCatalog() {
        val local = withContext(Dispatchers.IO) { catalog.getLocalSongs() }
        _songs.value = local
        _lastScannedAt.value = System.currentTimeMillis()
        Log.i(TAG, "reloadFromCatalog (local): ${local.size} tracks")
    }

    fun refresh() {
        if (_isLoading.value) return
        LibraryScanService.startLocal(context.applicationContext)
    }

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
            .groupBy { artistKey(it.effectiveAlbumArtist) }
            .mapNotNull { (key, tracks) ->
                if (key.isNullOrBlank()) return@mapNotNull null
                val displayName = tracks
                    .mapNotNull { primaryArtistName(it.effectiveAlbumArtist) ?: it.effectiveAlbumArtist }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                if (isCombinedArtistName(displayName)) return@mapNotNull null
                val deduped = tracks.distinctBy {
                    it.path?.lowercase() ?: it.contentUri.toString()
                }
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

    fun taggedCount(): Int = _songs.value.count { it.isTagged }
    fun untaggedCount(): Int = _songs.value.count { !it.isTagged }

    companion object {
        private const val TAG = "LibraryIndex"
        const val DEFAULT_STALE_MS = 12L * 60 * 60 * 1000
        /** Wait so MusicService can restore + user can hit Play first. */
        private const val COLD_EMPTY_RESCAN_DELAY_MS = 5_000L
        private const val STALE_RESCAN_DELAY_MS = 12_000L
        /** After a warm cache hit, wait before touching Room at all. */
        private const val ROOM_RECONCILE_DELAY_MS = 8_000L

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
    val displayArtist: String
        get() = primaryArtistName(artist) ?: artist ?: "Unknown Artist"
}

data class ArtistItem(
    val name: String?,
    val trackCount: Int,
    val albumCount: Int,
    val songs: List<Song>
) {
    val displayName: String get() = name ?: "Unknown Artist"
}
