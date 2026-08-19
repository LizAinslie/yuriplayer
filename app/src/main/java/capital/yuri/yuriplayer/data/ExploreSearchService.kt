package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.source.JellyfinClient
import capital.yuri.yuriplayer.data.source.LibrarySourceFactory
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceResolver
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Live, **source-tagged** music index for Explore.
 *
 * - Local files come from [LibraryIndex].
 * - Remote libraries (Jellyfin / Subsonic) are scanned progressively; each page
 *   is published into [remoteOfferings] immediately and persisted to the catalog
 *   with sourceType + sourceInstanceId.
 * - Tracks that lose every source are dropped from the index unless they are
 *   pinned in My Stuff.
 */
class ExploreSearchService(
    private val factory: LibrarySourceFactory,
    private val library: LibraryIndex,
    private val sourceResolver: SourceResolver,
    private val catalog: CatalogRepository,
    private val pinStore: MyStuffPinStore,
    private val instances: SourceInstanceRepository,
    private val jellyfinClient: JellyfinClient,
    private val subsonicClient: SubsonicClient
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

    /** Live remote offerings (source-tagged). Updated page-by-page during scan. */
    private val _remoteOfferings = MutableStateFlow<List<SourceOffering>>(emptyList())
    val remoteOfferings: StateFlow<List<SourceOffering>> = _remoteOfferings.asStateFlow()

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress: StateFlow<String?> = _scanProgress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _sourceCount = MutableStateFlow(0)
    val sourceCount: StateFlow<Int> = _sourceCount.asStateFlow()

    private var cacheAtMs: Long = 0L
    private var hydrated = false

    /** Load previously persisted remote catalog rows into the live index (once). */
    suspend fun hydrateFromCatalog() = withContext(Dispatchers.IO) {
        if (hydrated) return@withContext
        mutex.withLock {
            if (hydrated) return@withLock
            val rows = catalog.getRemoteOfferings()
            _remoteOfferings.value = rows
            _indexedCount.value = rows.size
            hydrated = true
            Log.i(TAG, "hydrated ${rows.size} remote offerings from catalog")
        }
    }

    suspend fun search(query: String, forceRescan: Boolean = false): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            hydrateFromCatalog()
            ensureRemoteIndex(forceRescan)

            buildHits(q)
        }

    /** Snapshot search against the current live index (no network). */
    fun searchLive(query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return buildHits(q)
    }

    private fun buildHits(needle: String): List<Hit> {
        val local = library.songs.value.map { song ->
            SourceOffering(
                sourceType = SourceType.LOCAL,
                sourceId = null,
                sourceName = "This device",
                song = song
            )
        }
        val all = local + _remoteOfferings.value
        val matched = all.filter { off ->
            val s = off.song
            s.displayTitle.contains(needle, true) ||
                s.displayArtist.contains(needle, true) ||
                s.displayAlbum.contains(needle, true) ||
                (s.path?.contains(needle, true) == true)
        }
        return matched
            .groupBy { trackIdentity(it.song) }
            .map { (key, offs) ->
                // Drop identity groups with zero offerings (shouldn't happen)
                val distinct = offs.distinctBy {
                    "${it.sourceType}:${it.sourceId}:${it.song.songKey}"
                }
                val preferred = runCatching {
                    // prefer is suspend — approximate with rank when called from non-suspend
                    distinct.minByOrNull { it.sourceType.rank } ?: distinct.first()
                }.getOrDefault(distinct.first())
                Hit(identityKey = key, offerings = distinct, preferred = preferred)
            }
            .sortedBy { it.song.displayTitle.lowercase() }
    }

    suspend fun searchWithPrefer(query: String, forceRescan: Boolean = false): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            hydrateFromCatalog()
            ensureRemoteIndex(forceRescan)

            val local = library.songs.value.map { song ->
                SourceOffering(
                    sourceType = SourceType.LOCAL,
                    sourceId = null,
                    sourceName = "This device",
                    song = song
                )
            }
            val matched = (local + _remoteOfferings.value).filter { off ->
                val s = off.song
                s.displayTitle.contains(q, true) ||
                    s.displayArtist.contains(q, true) ||
                    s.displayAlbum.contains(q, true) ||
                    (s.path?.contains(q, true) == true)
            }
            matched.groupBy { trackIdentity(it.song) }.map { (key, offs) ->
                val distinct = offs.distinctBy {
                    "${it.sourceType}:${it.sourceId}:${it.song.songKey}"
                }
                val preferred = sourceResolver.prefer(SCOPE_TRACK, key, distinct)
                    ?: distinct.minByOrNull { it.sourceType.rank }
                    ?: distinct.first()
                Hit(key, distinct, preferred)
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
        ensureRemoteIndex(force = true)
    }

    private suspend fun ensureRemoteIndex(force: Boolean) {
        mutex.withLock {
            val age = System.currentTimeMillis() - cacheAtMs
            if (!force && _remoteOfferings.value.isNotEmpty() && age < CACHE_TTL_MS) return
            _isScanning.value = true
            _lastError.value = null
            try {
                val rows = instances.getAll().filter { it.enabled }
                _sourceCount.value = rows.size
                if (rows.isEmpty()) {
                    _scanProgress.value = null
                    return
                }

                val keepKeys = pinStore.entries.value
                    .filter { it.kind == StuffPinKind.SONG }
                    .map { it.id }
                    .toSet()

                val rebuilt = _remoteOfferings.value.toMutableList()

                for (row in rows) {
                    val type = SourceType.from(row.type)
                    val sourceName = row.name
                    val instanceId = row.id
                    val seenAt = System.currentTimeMillis()

                    // Drop prior offerings for this instance; pages will refill live
                    rebuilt.removeAll {
                        it.sourceId == instanceId && it.sourceType == type
                    }
                    publish(rebuilt)

                    _scanProgress.value = "Scanning $sourceName…"

                    when (type) {
                        SourceType.JELLYFIN -> {
                            val url = row.baseUrl ?: continue
                            val user = row.username ?: continue
                            val secret = row.secret ?: continue
                            val session = jellyfinClient.authenticate(url, user, secret)
                                .getOrElse {
                                    Log.w(TAG, "jellyfin auth failed: ${it.message}")
                                    _lastError.value = "${sourceName}: ${it.message}"
                                    continue
                                }
                            jellyfinClient.listAudioItemsPaged(session, pageSize = 200) { page, start, total ->
                                val offs = page.map { song ->
                                    SourceOffering(
                                        sourceType = SourceType.JELLYFIN,
                                        sourceId = instanceId,
                                        sourceName = sourceName,
                                        song = song
                                    )
                                }
                                rebuilt.addAll(offs)
                                publish(rebuilt)
                                catalog.ingestRemoteBatch(
                                    songs = page,
                                    sourceType = CatalogSources.JELLYFIN,
                                    sourceInstanceId = instanceId,
                                    seenAt = seenAt
                                )
                                val totalPart = total?.let { " / $it" }.orEmpty()
                                _scanProgress.value =
                                    "$sourceName: ${start + page.size}$totalPart"
                            }.onFailure {
                                Log.w(TAG, "jellyfin scan failed: ${it.message}")
                                _lastError.value = "${sourceName}: ${it.message}"
                            }
                            catalog.pruneRemoteSource(
                                sourceType = CatalogSources.JELLYFIN,
                                sourceInstanceId = instanceId,
                                beforeMs = seenAt,
                                keepSongKeys = keepKeys
                            )
                        }
                        SourceType.SUBSONIC, SourceType.NAVIDROME -> {
                            val url = row.baseUrl ?: continue
                            val user = row.username ?: continue
                            val secret = row.secret ?: continue
                            val session = SubsonicClient.Session(url, user, secret)
                            runCatching {
                                subsonicClient.ping(session).getOrThrow()
                                val songs = subsonicClient.listAllSongs(session).getOrThrow()
                                // Publish in chunks so the UI still feels live
                                songs.chunked(200).forEachIndexed { i, chunk ->
                                    val offs = chunk.map { song ->
                                        SourceOffering(
                                            sourceType = type,
                                            sourceId = instanceId,
                                            sourceName = sourceName,
                                            song = song
                                        )
                                    }
                                    rebuilt.addAll(offs)
                                    publish(rebuilt)
                                    catalog.ingestRemoteBatch(
                                        songs = chunk,
                                        sourceType = if (type == SourceType.NAVIDROME)
                                            CatalogSources.NAVIDROME else CatalogSources.SUBSONIC,
                                        sourceInstanceId = instanceId,
                                        seenAt = seenAt
                                    )
                                    _scanProgress.value =
                                        "$sourceName: ${minOf((i + 1) * 200, songs.size)} / ${songs.size}"
                                }
                                catalog.pruneRemoteSource(
                                    sourceType = if (type == SourceType.NAVIDROME)
                                        CatalogSources.NAVIDROME else CatalogSources.SUBSONIC,
                                    sourceInstanceId = instanceId,
                                    beforeMs = seenAt,
                                    keepSongKeys = keepKeys
                                )
                            }.onFailure {
                                Log.w(TAG, "subsonic scan failed: ${it.message}")
                                _lastError.value = "${sourceName}: ${it.message}"
                            }
                        }
                        else -> Unit
                    }
                }

                // Drop any remaining remote offerings that somehow have no source tag
                // (shouldn't happen) unless My Stuff keeps them.
                val cleaned = rebuilt.filter {
                    it.sourceType != SourceType.OTHER || it.song.songKey in keepKeys
                }
                publish(cleaned)
                cacheAtMs = System.currentTimeMillis()
                Log.i(TAG, "remote index ready: ${cleaned.size} offerings")
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Scan failed"
                Log.e(TAG, "remote index failed", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }
    }

    private fun publish(list: List<SourceOffering>) {
        _remoteOfferings.value = list.toList()
        _indexedCount.value = list.size
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
