package capital.yuri.yuriplayer.data

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.source.JellyfinClient
import capital.yuri.yuriplayer.data.source.LibrarySourceFactory
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceResolver
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live, **source-tagged** music index for Explore.
 *
 * Heavy remote scans run in [LibraryScanService] with a live notification.
 * For large libraries (tens of thousands of tracks):
 *  - per-page work is **only** Room upserts (no album/artist rollups, no StateFlow list copies)
 *  - warm sources are skipped unless [force]
 *  - one rollup + one catalog hydrate happens at the end
 */
class ExploreSearchService(
    private val context: Context,
    private val factory: LibrarySourceFactory,
    private val library: LibraryIndex,
    private val sourceResolver: SourceResolver,
    private val catalog: CatalogRepository,
    private val pinStore: MyStuffPinStore,
    private val instances: SourceInstanceRepository,
    private val jellyfinClient: JellyfinClient,
    private val subsonicClient: SubsonicClient,
    private val notifier: LibraryScanNotifier
) {
    data class Hit(
        val identityKey: String,
        val offerings: List<SourceOffering>,
        val preferred: SourceOffering
    ) {
        val song: Song get() = preferred.song
        val isMultiSource: Boolean
            get() = offerings
                .map { "${it.sourceType.name}:${it.sourceId}" }
                .toSet()
                .size > 1
        val isExplicit: Boolean get() = offerings.any { it.song.isExplicit }
    }

    private val mutex = Mutex()
    private val jellyfinSessions = mutableMapOf<Long, JellyfinClient.Session>()
    private val cachedAlbumArtKeys = mutableSetOf<String>()
    private val budget = ScanBudget(context.applicationContext)
    private val lastPublishAt = AtomicLong(0L)
    private val pageCounter = AtomicInteger(0)

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

    suspend fun hydrateFromCatalog() = withContext(Dispatchers.IO) {
        if (hydrated && _remoteOfferings.value.isNotEmpty()) return@withContext
        mutex.withLock {
            if (hydrated && _remoteOfferings.value.isNotEmpty()) return@withLock
            val rows = catalog.getRemoteOfferings()
            publish(rows, force = true)
            hydrated = true
            if (rows.isNotEmpty()) cacheAtMs = System.currentTimeMillis()
            Log.i(TAG, "hydrated ${rows.size} remote offerings from catalog")
        }
    }

    fun requestRemoteScan(force: Boolean = false) {
        val age = System.currentTimeMillis() - cacheAtMs
        if (!force && _remoteOfferings.value.isNotEmpty() && age < CACHE_TTL_MS) {
            Log.i(TAG, "requestRemoteScan skipped — cache warm (${_remoteOfferings.value.size})")
            return
        }
        if (!force && _isScanning.value) return
        Log.i(
            TAG,
            "requestRemoteScan force=$force offerings=${_remoteOfferings.value.size} " +
                "device=${budget.deviceClass} page=${budget.pageSize}"
        )
        LibraryScanService.startRemote(context.applicationContext, force)
    }

    suspend fun refreshRemotes() {
        requestRemoteScan(force = true)
    }

    suspend fun runRemoteScanBlocking(force: Boolean) {
        runRemoteScan(force)
    }

    suspend fun search(query: String, forceRescan: Boolean = false): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            hydrateFromCatalog()
            requestRemoteScan(forceRescan)
            buildHitsPrefer(q)
        }

    suspend fun searchWithPrefer(query: String, forceRescan: Boolean = false): List<Hit> =
        search(query, forceRescan)

    fun searchLive(query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return buildHitsRankOnly(q)
    }

    private suspend fun buildHitsPrefer(needle: String): List<Hit> {
        val matched = collectMatched(needle)
        return matched.groupBy { trackIdentity(it.song) }.map { (key, offs) ->
            val distinct = dedupeOfferings(offs)
            val preferred = sourceResolver.prefer(SCOPE_TRACK, key, distinct)
                ?: distinct.minByOrNull { it.sourceType.rank }
                ?: distinct.first()
            Hit(key, distinct, preferred)
        }.sortedBy { it.song.displayTitle.lowercase() }
    }

    private fun buildHitsRankOnly(needle: String): List<Hit> {
        val matched = collectMatched(needle)
        return matched.groupBy { trackIdentity(it.song) }.map { (key, offs) ->
            val distinct = dedupeOfferings(offs)
            val preferred = distinct.minByOrNull { it.sourceType.rank } ?: distinct.first()
            Hit(key, distinct, preferred)
        }.sortedBy { it.song.displayTitle.lowercase() }
    }

    private fun dedupeOfferings(offs: List<SourceOffering>): List<SourceOffering> =
        offs.distinctBy { "${it.sourceType.name}:${it.sourceId}" }

    private fun collectMatched(needle: String): List<SourceOffering> {
        val local = library.songs.value
            .filter { song -> isLocalFilesystemSong(song) }
            .map { song ->
                SourceOffering(
                    sourceType = SourceType.LOCAL,
                    sourceId = null,
                    sourceName = "This device",
                    song = song
                )
            }
        val remoteKeys = _remoteOfferings.value.map { it.song.songKey }.toSet()
        val localOnly = local.filter { it.song.songKey !in remoteKeys }
        return (localOnly + _remoteOfferings.value).filter { off ->
            val s = off.song
            s.displayTitle.contains(needle, true) ||
                s.displayArtist.contains(needle, true) ||
                s.displayAlbum.contains(needle, true) ||
                (s.path?.contains(needle, true) == true)
        }
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

    private suspend fun runRemoteScan(force: Boolean) {
        if (!mutex.tryLock()) {
            Log.i(TAG, "scan already running — skip")
            return
        }
        try {
            val age = System.currentTimeMillis() - cacheAtMs
            if (!force && _remoteOfferings.value.isNotEmpty() && age < CACHE_TTL_MS) {
                Log.i(TAG, "skip scan — cache warm (${_remoteOfferings.value.size})")
                return
            }

            _isScanning.value = true
            _lastError.value = null
            pageCounter.set(0)
            notifier.update("Syncing libraries", "Connecting…")
            Log.i(TAG, "remote scan start force=$force device=${budget.deviceClass} page=${budget.pageSize}")

            val rows = instances.getAll().filter { it.enabled }
            _sourceCount.value = rows.size
            if (rows.isEmpty()) {
                _scanProgress.value = null
                progress("No remote sources enabled")
                return
            }

            val keepKeys = pinStore.entries.value
                .filter { it.kind == StuffPinKind.SONG }
                .map { it.id }
                .toSet()

            var totalIngested = 0
            var anySourceScanned = false

            for (row in rows) {
                val type = SourceType.from(row.type)
                val sourceName = row.name
                val instanceId = row.id
                val seenAt = System.currentTimeMillis()

                // Skip sources that are already well-indexed unless force
                if (!force) {
                    val catalogType = when (type) {
                        SourceType.JELLYFIN -> CatalogSources.JELLYFIN
                        SourceType.NAVIDROME -> CatalogSources.NAVIDROME
                        SourceType.SUBSONIC -> CatalogSources.SUBSONIC
                        else -> null
                    }
                    if (catalogType != null) {
                        val existing = catalog.countTracksForSource(catalogType, instanceId)
                        if (existing >= WARM_SOURCE_MIN_TRACKS) {
                            Log.i(TAG, "skip $sourceName — already indexed $existing tracks (use force to re-sync)")
                            progress("$sourceName: already indexed ($existing)")
                            totalIngested += existing
                            _indexedCount.value = totalIngested
                            continue
                        }
                    }
                }

                anySourceScanned = true
                progress("Scanning $sourceName…")

                val ingested = when (type) {
                    SourceType.JELLYFIN -> scanJellyfin(
                        rowId = instanceId,
                        sourceName = sourceName,
                        baseUrl = row.baseUrl,
                        username = row.username,
                        secret = row.secret,
                        seenAt = seenAt,
                        keepKeys = keepKeys
                    )
                    SourceType.SUBSONIC, SourceType.NAVIDROME -> scanSubsonic(
                        type = type,
                        instanceId = instanceId,
                        sourceName = sourceName,
                        baseUrl = row.baseUrl,
                        username = row.username,
                        secret = row.secret,
                        seenAt = seenAt,
                        keepKeys = keepKeys
                    )
                    else -> 0
                }
                totalIngested += ingested
                _indexedCount.value = totalIngested
            }

            if (!anySourceScanned && !force) {
                // Everything was warm — just make sure UI has catalog snapshot
                progress("Loading index…")
                val rowsSnap = catalog.getRemoteOfferings()
                publish(rowsSnap, force = true)
                cacheAtMs = System.currentTimeMillis()
                runCatching { library.reloadFromCatalog() }
                Log.i(TAG, "all sources warm — published ${rowsSnap.size} from catalog")
                return
            }

            // Single expensive pass: rollups + in-memory index + UI snapshot
            progress("Building album / artist index…")
            runCatching { catalog.rebuildRollups() }

            progress("Loading index…")
            val finalRows = catalog.getRemoteOfferings()
            publish(finalRows, force = true)
            cacheAtMs = System.currentTimeMillis()
            hydrated = true

            progress("Merging into library…")
            runCatching { library.reloadFromCatalog() }

            // Optional cover polish — capped, never blocks indexing
            progress("Caching covers…")
            runCatching { cacheAlbumArtPassive(finalRows.map { it.song }) }

            Log.i(TAG, "remote index ready: ${finalRows.size} offerings")
        } catch (e: CancellationException) {
            Log.i(TAG, "remote scan cancelled")
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Scan failed"
            Log.e(TAG, "remote index failed", e)
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
            mutex.unlock()
        }
    }

    private fun progress(text: String) {
        _scanProgress.value = text
        notifier.update("Syncing libraries", text)
    }

    /**
     * @return number of tracks ingested for this source
     */
    private suspend fun scanJellyfin(
        rowId: Long,
        sourceName: String,
        baseUrl: String?,
        username: String?,
        secret: String?,
        seenAt: Long,
        keepKeys: Set<String>
    ): Int {
        val url = baseUrl ?: return 0
        val user = username ?: return 0
        val pass = secret ?: return 0

        val session = jellyfinSessions[rowId] ?: jellyfinClient.authenticate(url, user, pass)
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "jellyfin auth failed: ${it.message}")
                _lastError.value = "$sourceName: ${it.message}"
                return 0
            }.also { jellyfinSessions[rowId] = it }

        var delivered = 0

        jellyfinClient.listAudioItemsPaged(session, pageSize = budget.pageSize) { page, start, total ->
            // DB only — no StateFlow list copies, no rollups
            catalog.ingestRemoteBatch(
                songs = page,
                sourceType = CatalogSources.JELLYFIN,
                sourceInstanceId = rowId,
                seenAt = seenAt
            )
            delivered += page.size
            _indexedCount.value = delivered

            val totalPart = total?.let { " / $it" }.orEmpty()
            val done = start + page.size
            // Progress text + notification only (no huge list publish)
            val n = pageCounter.incrementAndGet()
            if (n == 1 || n % budget.progressEveryPages == 0 || (total != null && done >= total)) {
                progress("$sourceName: $done$totalPart")
                if (total != null && total > 0) {
                    notifier.update("Syncing libraries", "$sourceName: $done / $total", done, total)
                }
            }

            budget.yieldBetweenPages()
            if (budget.deviceClass != ScanBudget.Class.HIGH && done % 2_000 == 0) {
                delay(budget.pageYieldMs * 2)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            jellyfinSessions.remove(rowId)
            Log.w(TAG, "jellyfin scan failed: ${it.message}")
            _lastError.value = "$sourceName: ${it.message}"
        }

        catalog.pruneRemoteSource(
            sourceType = CatalogSources.JELLYFIN,
            sourceInstanceId = rowId,
            beforeMs = seenAt,
            keepSongKeys = keepKeys
        )
        return delivered
    }

    /**
     * Slow, limited cover download after the index is searchable.
     * Never blocks page ingestion.
     */
    private suspend fun cacheAlbumArtPassive(songs: List<Song>) {
        val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
        var attempted = 0
        val seen = mutableSetOf<String>()
        for (song in songs) {
            if (attempted >= budget.artBatchLimit) break
            val aKey = albumKey(song.album, song.effectiveAlbumArtist)
            if (aKey in seen || aKey in cachedAlbumArtKeys) continue
            seen += aKey
            val url = song.albumArtUri?.toString() ?: continue
            if (!url.startsWith("http", ignoreCase = true)) continue
            val dest = File(
                coversDir,
                MetadataEnrichmentService.sanitizeFileName(aKey) + ".jpg"
            )
            if (dest.isFile && dest.length() > 0) {
                cachedAlbumArtKeys += aKey
                runCatching { catalog.applyAlbumCover(aKey, dest.absolutePath, url, null) }
                continue
            }
            val ok = runCatching {
                AlbumArtResolver.downloadToFile(url, dest, maxSize = 320)
            }.getOrDefault(false)
            if (ok) {
                cachedAlbumArtKeys += aKey
                runCatching { catalog.applyAlbumCover(aKey, dest.absolutePath, url, null) }
            }
            attempted++
            budget.yieldBetweenArt()
        }
        Log.i(TAG, "passive art pass: attempted=$attempted cached=${cachedAlbumArtKeys.size}")
    }

    private suspend fun scanSubsonic(
        type: SourceType,
        instanceId: Long,
        sourceName: String,
        baseUrl: String?,
        username: String?,
        secret: String?,
        seenAt: Long,
        keepKeys: Set<String>
    ): Int {
        val url = baseUrl ?: return 0
        val user = username ?: return 0
        val pass = secret ?: return 0
        val session = SubsonicClient.Session(url, user, pass)
        val catalogType =
            if (type == SourceType.NAVIDROME) CatalogSources.NAVIDROME else CatalogSources.SUBSONIC

        var delivered = 0
        runCatching {
            subsonicClient.ping(session).getOrThrow()
            val songs = subsonicClient.listAllSongs(session).getOrThrow()
            songs.chunked(budget.pageSize).forEachIndexed { i, chunk ->
                catalog.ingestRemoteBatch(
                    songs = chunk,
                    sourceType = catalogType,
                    sourceInstanceId = instanceId,
                    seenAt = seenAt
                )
                delivered += chunk.size
                _indexedCount.value = delivered
                val done = minOf((i + 1) * budget.pageSize, songs.size)
                if (i == 0 || (i + 1) % budget.progressEveryPages == 0 || done >= songs.size) {
                    progress("$sourceName: $done / ${songs.size}")
                    notifier.update(
                        "Syncing libraries",
                        "$sourceName: $done / ${songs.size}",
                        done,
                        songs.size
                    )
                }
                budget.yieldBetweenPages()
            }
            catalog.pruneRemoteSource(
                sourceType = catalogType,
                sourceInstanceId = instanceId,
                beforeMs = seenAt,
                keepSongKeys = keepKeys
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "subsonic scan failed: ${it.message}")
            _lastError.value = "$sourceName: ${it.message}"
        }
        return delivered
    }

    /**
     * @param force always push a new list snapshot (end of scan / hydrate).
     * During a large scan we intentionally do **not** publish intermediate lists —
     * only [indexedCount] / progress text update so the main thread stays responsive.
     */
    private fun publish(list: List<SourceOffering>, force: Boolean) {
        _indexedCount.value = list.size
        val now = System.currentTimeMillis()
        val last = lastPublishAt.get()
        if (!force && now - last < budget.publishMinIntervalMs) return
        if (!lastPublishAt.compareAndSet(last, now) && !force) return
        _remoteOfferings.value = ArrayList(list)
        if (force) lastPublishAt.set(now)
    }

    companion object {
        private const val TAG = "ExploreSearch"
        private const val SCOPE_TRACK = "track"
        private const val CACHE_TTL_MS = 15L * 60 * 1000
        /** If a source already has at least this many tracks, skip non-force re-sync. */
        private const val WARM_SOURCE_MIN_TRACKS = 50

        fun trackIdentity(song: Song): String {
            val t = song.title?.trim()?.lowercase().orEmpty()
            val a = (song.effectiveAlbumArtist ?: song.artist)?.trim()?.lowercase().orEmpty()
            val al = song.album?.trim()?.lowercase().orEmpty()
            return if (t.isNotEmpty() || a.isNotEmpty()) "$t|$a|$al"
            else song.songKey
        }

        fun isLocalFilesystemSong(song: Song): Boolean {
            val p = song.path ?: return song.contentUri.scheme == "content" ||
                song.contentUri.scheme == "file"
            if (p.startsWith("jellyfin:", ignoreCase = true)) return false
            if (p.startsWith("subsonic:", ignoreCase = true)) return false
            if (p.startsWith("navidrome:", ignoreCase = true)) return false
            if (p.contains("://")) return false
            return true
        }
    }
}
