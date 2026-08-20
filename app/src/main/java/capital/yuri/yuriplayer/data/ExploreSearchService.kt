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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Explore search + remote sync orchestration.
 *
 * Scans run in [LibraryScanService] (FGS). Leaving Explore never cancels work.
 * Per-source progress is checkpointed so pause/stop/resume pick up the cursor.
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
    private val notifier: LibraryScanNotifier,
    private val settings: LibrarySettings,
    private val checkpoints: ScanCheckpointStore
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
    private val pageCounter = AtomicInteger(0)
    private val lastCountPublishAt = AtomicLong(0L)

    private val pauseAll = AtomicBoolean(false)
    private val stopAll = AtomicBoolean(false)
    private val pausedSources = ConcurrentHashMap.newKeySet<Long>()
    private val stoppedSources = ConcurrentHashMap.newKeySet<Long>()

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

    private val _checkpoints = MutableStateFlow<List<SourceScanCheckpoint>>(emptyList())
    val sourceCheckpoints: StateFlow<List<SourceScanCheckpoint>> = _checkpoints.asStateFlow()

    private var cacheAtMs: Long = 0L
    private var hydrated = false

    init {
        refreshCheckpointSnapshot()
    }

    private fun refreshCheckpointSnapshot() {
        _checkpoints.value = checkpoints.all()
    }

    fun requestPauseAll() {
        pauseAll.set(true)
        Log.i(TAG, "pause all requested")
    }

    fun requestStopAll() {
        stopAll.set(true)
        pauseAll.set(true)
        Log.i(TAG, "stop all requested")
    }

    fun requestPauseSource(sourceInstanceId: Long) {
        pausedSources.add(sourceInstanceId)
        Log.i(TAG, "pause source $sourceInstanceId")
    }

    fun requestStopSource(sourceInstanceId: Long) {
        stoppedSources.add(sourceInstanceId)
        pausedSources.add(sourceInstanceId)
        Log.i(TAG, "stop source $sourceInstanceId")
    }

    fun clearControlFlags() {
        pauseAll.set(false)
        stopAll.set(false)
        pausedSources.clear()
        stoppedSources.clear()
    }

    private fun shouldAbortSource(sourceInstanceId: Long): Boolean =
        stopAll.get() || stoppedSources.contains(sourceInstanceId)

    private fun shouldPauseSource(sourceInstanceId: Long): Boolean =
        pauseAll.get() || pausedSources.contains(sourceInstanceId)

    suspend fun hydrateFromCatalog() = withContext(Dispatchers.IO) {
        if (hydrated && _indexedCount.value > 0) return@withContext
        mutex.withLock {
            if (hydrated && _indexedCount.value > 0) return@withLock
            val count = catalog.countRemoteTracks()
            _indexedCount.value = count
            hydrated = true
            if (count > 0) cacheAtMs = System.currentTimeMillis()
            refreshCheckpointSnapshot()
            Log.i(TAG, "hydrated remote count=$count (no full list loaded)")
        }
    }

    fun requestRemoteScan(force: Boolean = false) {
        if (!force && _isScanning.value) {
            Log.i(TAG, "requestRemoteScan ignored — already scanning")
            return
        }
        val age = System.currentTimeMillis() - cacheAtMs
        if (!force && _indexedCount.value > 0 && age < CACHE_TTL_MS) {
            val hasPaused = checkpoints.all().any {
                it.status == SourceScanStatus.PAUSED || it.status == SourceScanStatus.STOPPED
            }
            if (!hasPaused) {
                Log.i(TAG, "requestRemoteScan skipped — cache warm (${_indexedCount.value})")
                return
            }
        }

        if (!NetworkPolicy.allowsRemoteSync(context, settings)) {
            val reason = NetworkPolicy.blockedReason(context, settings)
                ?: "Remote sync blocked on this network"
            Log.i(TAG, "requestRemoteScan blocked: $reason")
            _lastError.value = reason
            return
        }

        if (force) clearControlFlags()
        Log.i(TAG, "requestRemoteScan force=$force indexed=${_indexedCount.value}")
        LibraryScanService.startRemote(context.applicationContext, force)
    }

    fun pauseScan(sourceId: Long? = null) {
        LibraryScanService.pause(context.applicationContext, sourceId)
    }

    fun stopScan(sourceId: Long? = null) {
        LibraryScanService.stop(context.applicationContext, sourceId)
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
            buildHitsFromDb(q, limit = 120)
        }

    suspend fun searchWithPrefer(query: String, forceRescan: Boolean = false): List<Hit> =
        search(query, forceRescan)

    suspend fun searchLive(query: String, limit: Int = 80): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            buildHitsFromDb(q, limit = limit)
        }

    private suspend fun buildHitsFromDb(needle: String, limit: Int): List<Hit> {
        val songs = catalog.searchSongs(needle, limit = limit * 3)
        if (songs.isEmpty()) return emptyList()

        val grouped = songs.groupBy { TrackIdentity.of(it) }
        val hits = ArrayList<Hit>(minOf(limit, grouped.size))
        for ((key, group) in grouped) {
            if (hits.size >= limit) break
            val offerings = group.map { song ->
                val type = sourceTypeForSong(song)
                SourceOffering(
                    sourceType = type,
                    sourceId = null,
                    sourceName = type.name.lowercase().replaceFirstChar { it.titlecase() },
                    song = song
                )
            }.distinctBy { "${it.sourceType.name}:${it.song.songKey}" }

            val preferredBase = sourceResolver.prefer(SCOPE_TRACK, key, offerings)
                ?: offerings.minByOrNull { it.sourceType.rank }
                ?: offerings.first()
            val preferred = preferredBase.copy(
                song = TrackIdentity.withRichestDisplay(
                    preferredBase.song,
                    offerings.map { it.song }
                )
            )
            hits += Hit(key, offerings, preferred)
        }
        hits.sortBy { it.song.displayTitle.lowercase() }
        return hits
    }

    private fun sourceTypeForSong(song: Song): SourceType {
        val p = song.path.orEmpty()
        return when {
            p.startsWith("jellyfin:", true) -> SourceType.JELLYFIN
            p.startsWith("navidrome:", true) -> SourceType.NAVIDROME
            p.startsWith("subsonic:", true) -> SourceType.SUBSONIC
            else -> SourceType.LOCAL
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

    private fun assertSyncAllowedOrThrow(): Boolean {
        if (NetworkPolicy.allowsRemoteSync(context, settings)) return true
        val reason = NetworkPolicy.blockedReason(context, settings)
            ?: "Remote sync blocked on this network"
        _lastError.value = reason
        progress(reason)
        Log.i(TAG, "sync paused: $reason")
        return false
    }

    private fun bumpIndexedCount(count: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastCountPublishAt.get()
        if (!force && now - last < budget.uiTickMinIntervalMs) return
        if (!lastCountPublishAt.compareAndSet(last, now) && !force) return
        _indexedCount.value = count
        if (force) lastCountPublishAt.set(now)
    }

    private suspend fun rebuildRollupsSafe(reason: String) {
        progress("Building album / artist index…")
        runCatching {
            catalog.rebuildRollups()
            Log.i(TAG, "rollups rebuilt ($reason)")
        }.onFailure {
            Log.w(TAG, "rollups failed ($reason): ${it.message}")
        }
    }

    private suspend fun runRemoteScan(force: Boolean) {
        if (!mutex.tryLock()) {
            Log.i(TAG, "scan already running — skip")
            return
        }
        try {
            clearControlFlags()
            if (!assertSyncAllowedOrThrow()) {
                runCatching { hydrateFromCatalog() }
                return
            }

            _isScanning.value = true
            _lastError.value = null
            pageCounter.set(0)
            notifier.update("Syncing libraries", "Connecting…")
            Log.i(TAG, "remote scan start force=$force")

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
            var abortedForNetwork = false

            for (row in rows) {
                if (stopAll.get()) break
                if (!assertSyncAllowedOrThrow()) {
                    abortedForNetwork = true
                    break
                }

                val type = SourceType.from(row.type)
                val sourceName = row.name
                val instanceId = row.id
                val seenAt = System.currentTimeMillis()
                val cp = checkpoints.get(instanceId)

                if (!force && cp?.status == SourceScanStatus.DONE) {
                    val existing = catalog.countTracksForSource(
                        when (type) {
                            SourceType.JELLYFIN -> CatalogSources.JELLYFIN
                            SourceType.NAVIDROME -> CatalogSources.NAVIDROME
                            SourceType.SUBSONIC -> CatalogSources.SUBSONIC
                            else -> CatalogSources.LOCAL
                        },
                        instanceId
                    )
                    progress("$sourceName: done ($existing)")
                    totalIngested += existing
                    bumpIndexedCount(totalIngested, force = true)
                    continue
                }

                if (!force && (cp == null || cp.status == SourceScanStatus.IDLE)) {
                    val catalogType = when (type) {
                        SourceType.JELLYFIN -> CatalogSources.JELLYFIN
                        SourceType.NAVIDROME -> CatalogSources.NAVIDROME
                        SourceType.SUBSONIC -> CatalogSources.SUBSONIC
                        else -> null
                    }
                    if (catalogType != null) {
                        val existing = catalog.countTracksForSource(catalogType, instanceId)
                        if (existing >= WARM_SOURCE_MIN_TRACKS) {
                            checkpoints.markDone(instanceId, sourceName, existing, existing)
                            progress("$sourceName: already indexed ($existing)")
                            totalIngested += existing
                            bumpIndexedCount(totalIngested, force = true)
                            continue
                        }
                    }
                }

                anySourceScanned = true
                val resumeFrom = if (force) 0 else (cp?.startIndex ?: 0)
                val priorDelivered = if (force) 0 else (cp?.delivered ?: 0)
                progress(
                    if (resumeFrom > 0) "Resuming $sourceName from $resumeFrom…"
                    else "Scanning $sourceName…"
                )

                val ingested = when (type) {
                    SourceType.JELLYFIN -> scanJellyfin(
                        rowId = instanceId,
                        sourceName = sourceName,
                        baseUrl = row.baseUrl,
                        username = row.username,
                        secret = row.secret,
                        seenAt = seenAt,
                        keepKeys = keepKeys,
                        startFrom = resumeFrom,
                        priorDelivered = priorDelivered
                    )
                    SourceType.SUBSONIC, SourceType.NAVIDROME -> scanSubsonic(
                        type = type,
                        instanceId = instanceId,
                        sourceName = sourceName,
                        baseUrl = row.baseUrl,
                        username = row.username,
                        secret = row.secret,
                        seenAt = seenAt,
                        keepKeys = keepKeys,
                        startAlbumOffset = resumeFrom,
                        priorDelivered = priorDelivered
                    )
                    else -> 0
                }
                if (ingested < 0) {
                    totalIngested += -ingested
                    if (abortedForNetwork || stopAll.get() || pauseAll.get()) break
                    continue
                }
                totalIngested += ingested
                bumpIndexedCount(totalIngested, force = true)
                if (ingested > 0) {
                    runCatching { catalog.rebuildRollups() }
                }
            }

            refreshCheckpointSnapshot()

            if (abortedForNetwork) {
                progress(
                    NetworkPolicy.blockedReason(context, settings)
                        ?: "Paused on mobile data"
                )
                val count = catalog.countRemoteTracks()
                bumpIndexedCount(count, force = true)
                if (count > 0) rebuildRollupsSafe("network pause")
                runCatching { library.reloadFromCatalog() }
                return
            }

            if (stopAll.get() || pauseAll.get()) {
                progress(if (stopAll.get()) "Stopped" else "Paused")
                val count = catalog.countRemoteTracks()
                bumpIndexedCount(count, force = true)
                if (count > 0) rebuildRollupsSafe(if (stopAll.get()) "stop" else "pause")
                runCatching { library.reloadFromCatalog() }
                return
            }

            if (!anySourceScanned && !force) {
                progress("Index ready")
                val count = catalog.countRemoteTracks()
                bumpIndexedCount(count, force = true)
                if (count > 0) rebuildRollupsSafe("warm skip")
                cacheAtMs = System.currentTimeMillis()
                runCatching { library.reloadFromCatalog() }
                return
            }

            rebuildRollupsSafe("scan complete")

            val finalCount = catalog.countRemoteTracks()
            bumpIndexedCount(finalCount, force = true)
            cacheAtMs = System.currentTimeMillis()
            hydrated = true

            progress("Merging local library…")
            runCatching { library.reloadFromCatalog() }

            if (NetworkPolicy.allowsRemoteSync(context, settings)) {
                progress("Caching covers…")
                runCatching {
                    val sample = catalog.getRemoteOfferings(limit = budget.artBatchLimit * 4)
                    cacheAlbumArtPassive(sample.map { it.song })
                }
            }

            Log.i(TAG, "remote index ready: count=$finalCount")
        } catch (e: CancellationException) {
            Log.i(TAG, "remote scan cancelled")
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Scan failed"
            Log.e(TAG, "remote index failed", e)
        } finally {
            _isScanning.value = false
            _scanProgress.value = null
            refreshCheckpointSnapshot()
            mutex.unlock()
        }
    }

    private fun progress(text: String) {
        _scanProgress.value = text
        notifier.update("Syncing libraries", text)
    }

    private suspend fun scanJellyfin(
        rowId: Long,
        sourceName: String,
        baseUrl: String?,
        username: String?,
        secret: String?,
        seenAt: Long,
        keepKeys: Set<String>,
        startFrom: Int,
        priorDelivered: Int
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

        var delivered = priorDelivered
        var cursor = startFrom
        var totalHint: Int? = checkpoints.get(rowId)?.totalHint
        var aborted = false
        var paused = false

        checkpoints.markRunning(rowId, sourceName, cursor, delivered, totalHint)

        jellyfinClient.listAudioItemsPaged(
            session = session,
            pageSize = budget.pageSize,
            startFromIndex = startFrom
        ) { page, start, total ->
            if (shouldAbortSource(rowId) || stopAll.get()) {
                aborted = true
                throw CancellationException("stopped by user")
            }
            if (shouldPauseSource(rowId)) {
                paused = true
                throw CancellationException("paused by user")
            }
            if (!assertSyncAllowedOrThrow()) {
                paused = true
                throw CancellationException("paused for mobile data policy")
            }

            catalog.ingestRemoteBatch(
                songs = page,
                sourceType = CatalogSources.JELLYFIN,
                sourceInstanceId = rowId,
                seenAt = seenAt
            )
            delivered += page.size
            cursor = start + page.size
            totalHint = total ?: totalHint
            bumpIndexedCount(delivered)

            checkpoints.markRunning(rowId, sourceName, cursor, delivered, totalHint)
            refreshCheckpointSnapshot()

            val totalPart = totalHint?.let { " / $it" }.orEmpty()
            val n = pageCounter.incrementAndGet()
            if (n == 1 || n % budget.progressEveryPages == 0 ||
                (totalHint != null && cursor >= totalHint!!)
            ) {
                progress("$sourceName: $delivered$totalPart")
                if (totalHint != null && totalHint!! > 0) {
                    notifier.update(
                        "Syncing libraries",
                        "$sourceName: $delivered / $totalHint",
                        delivered,
                        totalHint!!
                    )
                }
            }

            budget.yieldBetweenPages()
            if (budget.deviceClass != ScanBudget.Class.HIGH && delivered % 2_000 == 0) {
                delay(budget.pageYieldMs * 2)
            }
        }.onFailure {
            when {
                it is CancellationException && paused -> {
                    checkpoints.markPaused(rowId, sourceName, cursor, delivered, totalHint)
                    Log.i(TAG, "jellyfin paused $sourceName at cursor=$cursor delivered=$delivered")
                }
                it is CancellationException && aborted -> {
                    checkpoints.markStopped(rowId, sourceName, cursor, delivered, totalHint)
                    Log.i(TAG, "jellyfin stopped $sourceName at cursor=$cursor")
                }
                it is CancellationException -> throw it
                else -> {
                    jellyfinSessions.remove(rowId)
                    Log.w(TAG, "jellyfin scan failed: ${it.message}")
                    _lastError.value = "$sourceName: ${it.message}"
                }
            }
        }

        if (paused || aborted) {
            refreshCheckpointSnapshot()
            return -delivered.coerceAtLeast(1)
        }

        catalog.pruneRemoteSource(
            sourceType = CatalogSources.JELLYFIN,
            sourceInstanceId = rowId,
            beforeMs = seenAt,
            keepSongKeys = keepKeys
        )
        checkpoints.markDone(rowId, sourceName, delivered, totalHint)
        refreshCheckpointSnapshot()
        return delivered
    }

    private suspend fun cacheAlbumArtPassive(songs: List<Song>) {
        val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
        var attempted = 0
        val seen = mutableSetOf<String>()
        for (song in songs) {
            if (!NetworkPolicy.allowsRemoteSync(context, settings)) break
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

    /**
     * Subsonic / OpenSubsonic scan via getAlbumList2 paging.
     * [startAlbumOffset] is the album-list cursor (not song index).
     */
    private suspend fun scanSubsonic(
        type: SourceType,
        instanceId: Long,
        sourceName: String,
        baseUrl: String?,
        username: String?,
        secret: String?,
        seenAt: Long,
        keepKeys: Set<String>,
        startAlbumOffset: Int,
        priorDelivered: Int
    ): Int {
        val url = baseUrl ?: return 0
        val user = username ?: return 0
        val pass = secret ?: return 0
        val baseSession = SubsonicClient.Session(
            baseUrl = SourceInstanceRepository.normalizeBaseUrl(url),
            username = user,
            password = pass
        )
        val catalogType =
            if (type == SourceType.NAVIDROME) CatalogSources.NAVIDROME else CatalogSources.SUBSONIC

        val session = subsonicClient.ping(baseSession).getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "subsonic ping failed: ${it.message}")
            _lastError.value = "$sourceName: ${it.message}"
            return 0
        }

        var delivered = priorDelivered
        var albumOffset = startAlbumOffset.coerceAtLeast(0)
        var aborted = false
        var paused = false

        checkpoints.markRunning(instanceId, sourceName, albumOffset, delivered, null)

        subsonicClient.listSongsPaged(
            session = session,
            pageSize = minOf(budget.pageSize, 100),
            startAlbumOffset = albumOffset
        ) { songs, offset, albumsInPage, exhausted ->
            if (shouldAbortSource(instanceId) || stopAll.get()) {
                aborted = true
                throw CancellationException("stopped by user")
            }
            if (shouldPauseSource(instanceId)) {
                paused = true
                throw CancellationException("paused by user")
            }
            if (!assertSyncAllowedOrThrow()) {
                paused = true
                throw CancellationException("paused for mobile data policy")
            }

            if (songs.isNotEmpty()) {
                catalog.ingestRemoteBatch(
                    songs = songs,
                    sourceType = catalogType,
                    sourceInstanceId = instanceId,
                    seenAt = seenAt
                )
                delivered += songs.size
            }
            albumOffset = offset + albumsInPage
            bumpIndexedCount(delivered)
            checkpoints.markRunning(instanceId, sourceName, albumOffset, delivered, null)
            refreshCheckpointSnapshot()

            val n = pageCounter.incrementAndGet()
            if (n == 1 || n % budget.progressEveryPages == 0 || exhausted) {
                progress("$sourceName: $delivered tracks (albums @$albumOffset)")
                notifier.update(
                    "Syncing libraries",
                    "$sourceName: $delivered tracks",
                    delivered,
                    null
                )
            }

            budget.yieldBetweenPages()
        }.onFailure {
            when {
                it is CancellationException && paused -> {
                    checkpoints.markPaused(instanceId, sourceName, albumOffset, delivered, null)
                    Log.i(TAG, "subsonic paused $sourceName at albumOffset=$albumOffset delivered=$delivered")
                }
                it is CancellationException && aborted -> {
                    checkpoints.markStopped(instanceId, sourceName, albumOffset, delivered, null)
                    Log.i(TAG, "subsonic stopped $sourceName at albumOffset=$albumOffset")
                }
                it is CancellationException -> throw it
                else -> {
                    Log.w(TAG, "subsonic scan failed: ${it.message}")
                    _lastError.value = "$sourceName: ${it.message}"
                }
            }
        }

        if (paused || aborted) {
            refreshCheckpointSnapshot()
            return -delivered.coerceAtLeast(1)
        }

        catalog.pruneRemoteSource(
            sourceType = catalogType,
            sourceInstanceId = instanceId,
            beforeMs = seenAt,
            keepSongKeys = keepKeys
        )
        checkpoints.markDone(instanceId, sourceName, delivered, delivered)
        refreshCheckpointSnapshot()
        return delivered
    }

    companion object {
        private const val TAG = "ExploreSearch"
        private const val SCOPE_TRACK = "track"
        private const val CACHE_TTL_MS = 15L * 60 * 1000
        private const val WARM_SOURCE_MIN_TRACKS = 50

        fun trackIdentity(song: Song): String = TrackIdentity.of(song)

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
