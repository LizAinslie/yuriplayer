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
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Live, **source-tagged** music index for Explore.
 *
 * Heavy remote scans run in [LibraryScanService] with a live notification.
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
        /** True only when two or more *distinct* source instances provide this track. */
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
        if (hydrated) return@withContext
        mutex.withLock {
            if (hydrated) return@withLock
            val rows = catalog.getRemoteOfferings()
            publish(rows)
            hydrated = true
            if (rows.isNotEmpty()) cacheAtMs = System.currentTimeMillis()
            Log.i(TAG, "hydrated ${rows.size} remote offerings from catalog")
        }
    }

    fun requestRemoteScan(force: Boolean = false) {
        val age = System.currentTimeMillis() - cacheAtMs
        if (!force && _remoteOfferings.value.isNotEmpty() && age < CACHE_TTL_MS) return
        if (!force && _isScanning.value) return
        Log.i(TAG, "requestRemoteScan force=$force offerings=${_remoteOfferings.value.size}")
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

    /** One row per source instance (not per duplicate catalog row). */
    private fun dedupeOfferings(offs: List<SourceOffering>): List<SourceOffering> =
        offs.distinctBy { "${it.sourceType.name}:${it.sourceId}" }

    private fun collectMatched(needle: String): List<SourceOffering> {
        // Only treat real local filesystem tracks as LOCAL — virtual remote keys
        // that landed in LibraryIndex after reload must not become a second source.
        val local = library.songs.value
            .filter { song ->
                !isVirtualLibraryPath(song.path) &&
                    song.path?.contains("://") != true
            }
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
        mutex.withLock {
            val age = System.currentTimeMillis() - cacheAtMs
            if (!force && _remoteOfferings.value.isNotEmpty() && age < CACHE_TTL_MS) {
                Log.i(TAG, "skip scan — cache warm (${_remoteOfferings.value.size})")
                return
            }

            _isScanning.value = true
            _lastError.value = null
            notifier.update("Syncing libraries", "Connecting…")
            try {
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

                val rebuilt = _remoteOfferings.value.toMutableList()

                for (row in rows) {
                    val type = SourceType.from(row.type)
                    val sourceName = row.name
                    val instanceId = row.id
                    val seenAt = System.currentTimeMillis()

                    rebuilt.removeAll {
                        it.sourceId == instanceId && it.sourceType == type
                    }
                    publish(rebuilt)
                    progress("Scanning $sourceName…")

                    when (type) {
                        SourceType.JELLYFIN -> scanJellyfin(
                            rowId = instanceId,
                            sourceName = sourceName,
                            baseUrl = row.baseUrl,
                            username = row.username,
                            secret = row.secret,
                            seenAt = seenAt,
                            keepKeys = keepKeys,
                            rebuilt = rebuilt
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
                            rebuilt = rebuilt
                        )
                        else -> Unit
                    }
                }

                val cleaned = rebuilt.filter {
                    it.sourceType != SourceType.OTHER || it.song.songKey in keepKeys
                }
                publish(cleaned)
                cacheAtMs = System.currentTimeMillis()
                runCatching { library.reloadFromCatalog() }
                Log.i(TAG, "remote index ready: ${cleaned.size} offerings")
            } catch (e: CancellationException) {
                Log.i(TAG, "remote scan cancelled")
                throw e
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Scan failed"
                Log.e(TAG, "remote index failed", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
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
        rebuilt: MutableList<SourceOffering>
    ) {
        val url = baseUrl ?: return
        val user = username ?: return
        val pass = secret ?: return

        val session = jellyfinSessions[rowId] ?: jellyfinClient.authenticate(url, user, pass)
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "jellyfin auth failed: ${it.message}")
                _lastError.value = "$sourceName: ${it.message}"
                return
            }.also { jellyfinSessions[rowId] = it }

        jellyfinClient.listAudioItemsPaged(session, pageSize = 500) { page, start, total ->
            val offs = page.map { song ->
                SourceOffering(
                    sourceType = SourceType.JELLYFIN,
                    sourceId = rowId,
                    sourceName = sourceName,
                    song = song
                )
            }
            rebuilt.addAll(offs)
            publish(rebuilt)
            catalog.ingestRemoteBatch(
                songs = page,
                sourceType = CatalogSources.JELLYFIN,
                sourceInstanceId = rowId,
                seenAt = seenAt
            )
            cacheAlbumArtBatch(page)
            val totalPart = total?.let { " / $it" }.orEmpty()
            val done = start + page.size
            progress("$sourceName: $done$totalPart")
            if (total != null && total > 0) {
                notifier.update("Syncing libraries", "$sourceName: $done / $total", done, total)
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
    }

    private suspend fun cacheAlbumArtBatch(songs: List<Song>) {
        val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
        val seen = mutableSetOf<String>()
        for (song in songs) {
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
                catalog.applyAlbumCover(aKey, dest.absolutePath, url, null)
                continue
            }
            val ok = AlbumArtResolver.downloadToFile(url, dest, maxSize = 512)
            if (ok) {
                cachedAlbumArtKeys += aKey
                catalog.applyAlbumCover(aKey, dest.absolutePath, url, null)
            }
        }
    }

    private suspend fun scanSubsonic(
        type: SourceType,
        instanceId: Long,
        sourceName: String,
        baseUrl: String?,
        username: String?,
        secret: String?,
        seenAt: Long,
        keepKeys: Set<String>,
        rebuilt: MutableList<SourceOffering>
    ) {
        val url = baseUrl ?: return
        val user = username ?: return
        val pass = secret ?: return
        val session = SubsonicClient.Session(url, user, pass)
        val catalogType =
            if (type == SourceType.NAVIDROME) CatalogSources.NAVIDROME else CatalogSources.SUBSONIC

        runCatching {
            subsonicClient.ping(session).getOrThrow()
            val songs = subsonicClient.listAllSongs(session).getOrThrow()
            songs.chunked(500).forEachIndexed { i, chunk ->
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
                    sourceType = catalogType,
                    sourceInstanceId = instanceId,
                    seenAt = seenAt
                )
                cacheAlbumArtBatch(chunk)
                val done = minOf((i + 1) * 500, songs.size)
                progress("$sourceName: $done / ${songs.size}")
                notifier.update("Syncing libraries", "$sourceName: $done / ${songs.size}", done, songs.size)
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
