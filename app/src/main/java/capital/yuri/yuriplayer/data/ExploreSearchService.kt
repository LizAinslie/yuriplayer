package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.source.LibrarySourceFactory
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceResolver
import capital.yuri.yuriplayer.data.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Query-first Explore search across every [LibrarySource].
 *
 * Remote libraries are scanned on demand (and cached in memory for the session).
 * Hits that match the same title/artist/album are merged into one row with
 * multiple [SourceOffering]s; [SourceResolver] picks the preferred stream.
 */
class ExploreSearchService(
    private val factory: LibrarySourceFactory,
    private val library: LibraryIndex,
    private val sourceResolver: SourceResolver
) {
    data class Hit(
        val identityKey: String,
        val offerings: List<SourceOffering>,
        val preferred: SourceOffering
    ) {
        val song: Song get() = preferred.song
        val isMultiSource: Boolean get() = offerings.size > 1
        val isExplicit: Boolean get() = offerings.any { it.song.isExplicit }
    }

    private val mutex = Mutex()
    private var remoteCache: List<SourceOffering> = emptyList()
    private var cacheAtMs: Long = 0L

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _sourceCount = MutableStateFlow(0)
    val sourceCount: StateFlow<Int> = _sourceCount.asStateFlow()

    suspend fun search(query: String, forceRescan: Boolean = false): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            ensureRemoteCache(forceRescan)

            val local = library.songs.value.map { song ->
                SourceOffering(
                    sourceType = SourceType.LOCAL,
                    sourceId = null,
                    sourceName = "This device",
                    song = song
                )
            }
            val all = local + remoteCache
            val needle = q.lowercase()

            val matched = all.filter { off ->
                val s = off.song
                s.displayTitle.contains(needle, true) ||
                    s.displayArtist.contains(needle, true) ||
                    s.displayAlbum.contains(needle, true) ||
                    (s.path?.contains(needle, true) == true)
            }

            val grouped = matched.groupBy { trackIdentity(it.song) }
            grouped.map { (key, offs) ->
                val preferred = sourceResolver.prefer(
                    scope = SCOPE_TRACK,
                    scopeKey = key,
                    offerings = offs
                ) ?: offs.minByOrNull { it.sourceType.rank } ?: offs.first()
                Hit(
                    identityKey = key,
                    offerings = offs.distinctBy { "${it.sourceType}:${it.sourceId}:${it.song.songKey}" },
                    preferred = preferred
                )
            }.sortedBy { it.song.displayTitle.lowercase() }
        }

    suspend fun setPreferredSource(
        identityKey: String,
        offering: SourceOffering
    ) = withContext(Dispatchers.IO) {
        sourceResolver.setOverride(
            scope = SCOPE_TRACK,
            scopeKey = identityKey,
            sourceId = offering.sourceId,
            sourceType = offering.sourceType.name
        )
    }

    suspend fun clearPreferredSource(identityKey: String) = withContext(Dispatchers.IO) {
        sourceResolver.clearOverride(SCOPE_TRACK, identityKey)
    }

    suspend fun refreshRemotes() {
        ensureRemoteCache(force = true)
    }

    private suspend fun ensureRemoteCache(force: Boolean) {
        mutex.withLock {
            val age = System.currentTimeMillis() - cacheAtMs
            if (!force && remoteCache.isNotEmpty() && age < CACHE_TTL_MS) return
            _isScanning.value = true
            _lastError.value = null
            try {
                val sources = factory.buildAll().filter { it.type != SourceType.LOCAL }
                _sourceCount.value = sources.size
                val offerings = coroutineScope {
                    sources.map { src ->
                        async {
                            runCatching {
                                if (!src.isAvailable()) return@runCatching emptyList()
                                val snap = src.scan()
                                val instanceId = src.id.substringAfter(':', missingDelimiterValue = "")
                                    .toLongOrNull()
                                snap.songs.map { song ->
                                    SourceOffering(
                                        sourceType = src.type,
                                        sourceId = instanceId,
                                        sourceName = src.displayName,
                                        song = song
                                    )
                                }
                            }.onFailure {
                                Log.w(TAG, "scan ${src.id} failed: ${it.message}")
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
                remoteCache = offerings
                cacheAtMs = System.currentTimeMillis()
                Log.i(TAG, "remote cache: ${offerings.size} tracks from ${sources.size} sources")
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Scan failed"
                Log.e(TAG, "remote scan failed", e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    companion object {
        private const val TAG = "ExploreSearch"
        private const val SCOPE_TRACK = "track"
        private const val CACHE_TTL_MS = 15L * 60 * 1000

        fun trackIdentity(song: Song): String {
            val t = song.title?.trim()?.lowercase().orEmpty()
            val a = (song.effectiveAlbumArtist ?: song.artist)?.trim()?.lowercase().orEmpty()
            val al = song.album?.trim()?.lowercase().orEmpty()
            return if (t.isNotEmpty() || a.isNotEmpty()) "$t|$a|$al"
            else song.songKey
        }
    }
}
