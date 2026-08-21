package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.CoverPixels
import capital.yuri.yuriplayer.core.library.LocalLibraryScanner
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.core.player.PlayerSession
import capital.yuri.yuriplayer.core.source.JellyfinCatalog
import capital.yuri.yuriplayer.core.source.LibrarySourceStore
import capital.yuri.yuriplayer.core.source.RemoteAccount
import capital.yuri.yuriplayer.core.source.SourceKind
import capital.yuri.yuriplayer.core.source.SubsonicCatalog
import capital.yuri.yuriplayer.desktop.os.createOsMediaControls
import capital.yuri.yuriplayer.desktop.player.VlcjPlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class DesktopScanStatus { IDLE, RUNNING, PAUSED, STOPPED, DONE, ERROR }

data class DesktopScanSource(
    val id: String,
    val name: String,
    val status: DesktopScanStatus,
    val detail: String = "",
    val count: Int = 0
)

class DesktopSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val engine = VlcjPlaybackEngine()
    val player = PlayerSession(engine)
    private val media: OsMediaControls = createOsMediaControls()
    private val http = HttpClient(CIO)

    private val _engineMessage = MutableStateFlow(engine.nativeError)
    val engineMessage: StateFlow<String?> = _engineMessage.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanMessage = MutableStateFlow("Scanning…")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanSources = MutableStateFlow<List<DesktopScanSource>>(emptyList())
    val scanSources: StateFlow<List<DesktopScanSource>> = _scanSources.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _coverPixels = MutableStateFlow<IntArray?>(null)
    val coverPixels: StateFlow<IntArray?> = _coverPixels.asStateFlow()

    val dirs = appDirectories()
    val theme = DesktopThemeStore(dirs.configDir)
    val layout = DesktopLayoutStore(dirs.configDir)
    val collection = DesktopCollection(dirs.configDir)
    val playlists = DesktopPlaylistStore(dirs.configDir)
    val sources = LibrarySourceStore(dirs.configDir)
    val jellyfin = JellyfinCatalog(http, sources.deviceId)
    val subsonic = SubsonicCatalog(http)

    private var ticker: Job? = null
    private var scanJob: Job? = null
    private var sourceJob: Job? = null
    private val scanGate = Mutex()
    private val stopAll = AtomicBoolean(false)
    private val pauseAll = AtomicBoolean(false)
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppedIds = ConcurrentHashMap.newKeySet<String>()
    private val currentSourceId = AtomicReference<String?>(null)

    init {
        engine.addListener(object : capital.yuri.yuriplayer.core.player.PlaybackEngine.Listener {
            override fun onError(message: String, recoverable: Boolean) {
                _engineMessage.value = message
            }
        })
        media.attach(object : OsMediaControls.Callbacks {
            override fun onPlay() {
                if (!player.isPlaying.value) player.togglePlay()
            }
            override fun onPause() = player.pause()
            override fun onPlayPause() = player.togglePlay()
            override fun onStop() = player.stop()
            override fun onNext() = player.next()
            override fun onPrevious() = player.previous()
            override fun onSeek(positionMs: Long) = player.seekTo(positionMs)
            override fun onQuit() = release()
        })
        ticker = scope.launch {
            while (isActive) {
                _positionMs.value = player.positionMs()
                _durationMs.value = player.durationMs()
                media.update(
                    track = player.current.value,
                    playing = player.isPlaying.value,
                    positionMs = _positionMs.value,
                    durationMs = _durationMs.value
                )
                delay(250)
            }
        }
        scope.launch(Dispatchers.IO) {
            player.current.collect { track ->
                _coverPixels.value = CoverPixels.argb(track?.artworkUri, track?.path)
            }
        }
        scope.launch {
            sources.remotes.collect { refreshScanSources() }
        }
        rescan()
    }

    fun playTrack(track: Track) {
        val list = _tracks.value
        val i = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.play(if (list.isNotEmpty()) list else listOf(track), i)
    }

    fun replaceTracks(updated: List<Track>) {
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        _tracks.value = _tracks.value.map { byId[it.id] ?: it }
    }

    fun addFolder(path: String) {
        sources.addFolder(path)
        indexFolder(path)
    }

    fun removeFolder(path: String) {
        sources.removeFolder(path)
        rescan()
    }

    fun addJellyfin(name: String, baseUrl: String, username: String, password: String) {
        saveRemote(
            existingId = null,
            kind = SourceKind.JELLYFIN,
            name = name,
            baseUrl = baseUrl,
            username = username,
            password = password,
            enabled = true
        )
    }

    fun addSubsonic(name: String, baseUrl: String, username: String, password: String) {
        saveRemote(
            existingId = null,
            kind = SourceKind.SUBSONIC,
            name = name,
            baseUrl = baseUrl,
            username = username,
            password = password,
            enabled = true
        )
    }

    fun saveRemote(
        existingId: String?,
        kind: SourceKind,
        name: String,
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean,
        onDone: (ok: Boolean, message: String) -> Unit = { ok, msg ->
            if (!ok) _scanMessage.value = msg
        }
    ) {
        scope.launch(Dispatchers.IO) {
            val existing = existingId?.let { id -> sources.remotes.value.firstOrNull { it.id == id } }
            val seed = RemoteAccount(
                id = existing?.id ?: UUID.randomUUID().toString(),
                kind = kind,
                name = name.ifBlank {
                    if (kind == SourceKind.JELLYFIN) "Jellyfin" else "Subsonic"
                },
                baseUrl = baseUrl,
                username = username,
                secret = password.ifBlank { existing?.secret.orEmpty() },
                enabled = enabled,
                accessToken = existing?.accessToken,
                userId = existing?.userId
            )
            val result = when (kind) {
                SourceKind.JELLYFIN -> jellyfin.authenticate(seed)
                SourceKind.SUBSONIC -> subsonic.ping(seed)
                SourceKind.LOCAL -> Result.failure(IllegalArgumentException("Not a remote provider"))
            }
            result
                .onSuccess { signed ->
                    val saved = signed.copy(enabled = enabled, name = seed.name)
                    sources.upsertRemote(saved)
                    if (enabled) indexRemote(saved)
                    withContext(Dispatchers.Swing) { onDone(true, "Saved — indexing ${saved.name}") }
                }
                .onFailure {
                    withContext(Dispatchers.Swing) {
                        onDone(false, it.message ?: "Could not reach server")
                    }
                }
        }
    }

    suspend fun testConnection(
        kind: SourceKind,
        url: String,
        username: String,
        password: String
    ): Result<String> {
        val seed = RemoteAccount(
            id = "test",
            kind = kind,
            name = "",
            baseUrl = url,
            username = username,
            secret = password
        )
        return when (kind) {
            SourceKind.JELLYFIN -> jellyfin.authenticate(seed).map { signed ->
                val who = signed.userId?.take(8)?.plus("…") ?: signed.username
                "Connected as $who"
            }
            SourceKind.SUBSONIC -> subsonic.ping(seed).map { "Ping ok" }
            SourceKind.LOCAL -> Result.failure(IllegalArgumentException("Not a remote provider"))
        }
    }

    fun setRemoteEnabled(id: String, enabled: Boolean) {
        sources.setRemoteEnabled(id, enabled)
        if (enabled) {
            sources.remotes.value.firstOrNull { it.id == id }?.let { indexRemote(it) }
        }
    }

    fun removeRemote(id: String) {
        sources.removeRemote(id)
        rescan()
    }

    fun indexFolder(path: String) {
        requestScan(force = true, sourceId = LOCAL_SCAN_ID)
    }

    fun indexRemote(account: RemoteAccount) {
        if (!account.enabled || account.kind == SourceKind.LOCAL) return
        requestScan(force = true, sourceId = account.id)
    }

    private fun mergeTracks(incoming: List<Track>) {
        if (incoming.isEmpty()) return
        _tracks.value = (_tracks.value + incoming).distinctBy { it.id }
    }

    fun rescan() = requestScan(force = true)

    fun requestScan(force: Boolean = false, sourceId: String? = null) {
        if (force && sourceId == null) {
            pausedIds.clear()
            stoppedIds.clear()
            sourceJob?.cancel()
            scanJob?.cancel()
        }
        stopAll.set(false)
        pauseAll.set(false)
        if (sourceId != null) {
            pausedIds.remove(sourceId)
            stoppedIds.remove(sourceId)
        } else if (force) {
            pausedIds.clear()
            stoppedIds.clear()
        }
        scanJob = scope.launch(Dispatchers.IO) {
            scanGate.withLock {
                runScan(force = force, onlySourceId = sourceId)
            }
        }
    }

    fun pauseScan(sourceId: String? = null) {
        if (sourceId == null) {
            pauseAll.set(true)
            sourceJob?.cancel()
            markRunning(DesktopScanStatus.PAUSED, "Paused")
            _scanMessage.value = "Paused"
        } else {
            pausedIds.add(sourceId)
            patchScan(sourceId, DesktopScanStatus.PAUSED, "Paused")
            if (currentSourceId.get() == sourceId) sourceJob?.cancel()
        }
    }

    fun stopScan(sourceId: String? = null) {
        if (sourceId == null) {
            stopAll.set(true)
            sourceJob?.cancel()
            markRunning(DesktopScanStatus.STOPPED, "Stopped")
            _isScanning.value = false
            _scanMessage.value = "Stopped"
        } else {
            stoppedIds.add(sourceId)
            patchScan(sourceId, DesktopScanStatus.STOPPED, "Stopped")
            if (currentSourceId.get() == sourceId) sourceJob?.cancel()
        }
    }

    private suspend fun runScan(force: Boolean, onlySourceId: String?) {
        _isScanning.value = true
        refreshScanSources()
        try {
            coroutineScope {
                val doLocal = onlySourceId == null || onlySourceId == LOCAL_SCAN_ID
                val remotes = sources.remotes.value.filter { it.enabled }
                    .filter { onlySourceId == null || it.id == onlySourceId }

                val localTracks = if (doLocal) {
                    scanOneSource(LOCAL_SCAN_ID, "Local library", force, onlySourceId) {
                        scanLocalLibrary()
                    }
                } else {
                    emptyList()
                }
                if (halted()) return@coroutineScope

                val remoteTracks = mutableListOf<Track>()
                for (remote in remotes) {
                    if (halted()) break
                    val got = scanOneSource(remote.id, remote.name, force, onlySourceId) {
                        fetchRemote(remote, partial = !force)
                    }
                    remoteTracks += got
                }

                if (stopAll.get() || pauseAll.get()) return@coroutineScope

                if (onlySourceId == null && force) {
                    _tracks.value = (localTracks + remoteTracks).distinctBy { it.id }
                } else {
                    mergeTracks(localTracks + remoteTracks)
                }
                _scanMessage.value = when {
                    _tracks.value.isEmpty() ->
                        "No tracks yet — add a folder or a server in Settings → Library"
                    else -> "${_tracks.value.size} tracks"
                }
            }
        } catch (e: CancellationException) {
            if (pauseAll.get()) {
                markRunning(DesktopScanStatus.PAUSED, "Paused")
                _scanMessage.value = "Paused"
            } else {
                markRunning(DesktopScanStatus.STOPPED, "Stopped")
                _scanMessage.value = "Stopped"
            }
            throw e
        } finally {
            currentSourceId.set(null)
            sourceJob = null
            if (!pauseAll.get()) {
                // keep isScanning true only while a source child is in flight
            }
            _isScanning.value = false
        }
    }

    private fun halted(): Boolean = stopAll.get() || pauseAll.get()

    private fun shouldScan(id: String, force: Boolean, onlySourceId: String?): Boolean {
        if (id in stoppedIds && onlySourceId != id) return false
        if (id in pausedIds && onlySourceId != id && !force) return false
        val src = _scanSources.value.firstOrNull { it.id == id }
        if (force || onlySourceId == id) return true
        // Partial all: skip already-indexed sources.
        if (src?.status == DesktopScanStatus.DONE && (src.count > 0)) return false
        return true
    }

    private suspend fun scanOneSource(
        id: String,
        name: String,
        force: Boolean,
        onlySourceId: String?,
        block: suspend () -> List<Track>
    ): List<Track> {
        if (!shouldScan(id, force, onlySourceId)) {
            val src = _scanSources.value.firstOrNull { it.id == id }
            if (src?.status == DesktopScanStatus.DONE) {
                _scanMessage.value = "$name: indexed (${src.count}) — skip"
            }
            return emptyList()
        }
        if (id in stoppedIds) {
            patchScan(id, DesktopScanStatus.STOPPED, "Stopped")
            return emptyList()
        }
        if (id in pausedIds || pauseAll.get()) {
            patchScan(id, DesktopScanStatus.PAUSED, "Paused")
            return emptyList()
        }
        if (stopAll.get()) {
            patchScan(id, DesktopScanStatus.STOPPED, "Stopped")
            return emptyList()
        }
        currentSourceId.set(id)
        patchScan(id, DesktopScanStatus.RUNNING, if (force) "Full scan…" else "Checking…")
        _scanMessage.value = if (force) "Full scan · $name" else "Partial scan · $name"
        return coroutineScope {
            val child = async { block() }
            sourceJob = child
            try {
                val incoming = child.await()
                val prev = _scanSources.value.firstOrNull { it.id == id }?.count ?: 0
                val nextCount = if (force) incoming.size else maxOf(prev, incoming.size)
                patchScan(
                    id,
                    DesktopScanStatus.DONE,
                    when {
                        incoming.isEmpty() && !force -> "up to date"
                        incoming.isEmpty() -> "0 tracks"
                        else -> "${incoming.size} tracks"
                    },
                    nextCount
                )
                incoming
            } catch (e: CancellationException) {
                when {
                    stopAll.get() || id in stoppedIds ->
                        patchScan(id, DesktopScanStatus.STOPPED, "Stopped")
                    pauseAll.get() || id in pausedIds ->
                        patchScan(id, DesktopScanStatus.PAUSED, "Paused")
                    else -> patchScan(id, DesktopScanStatus.STOPPED, "Stopped")
                }
                if (halted()) throw e
                emptyList()
            } catch (e: Exception) {
                patchScan(id, DesktopScanStatus.ERROR, e.message ?: "Failed")
                _scanMessage.value = "$name: ${e.message}"
                emptyList()
            } finally {
                if (currentSourceId.get() == id) currentSourceId.set(null)
                if (sourceJob === child) sourceJob = null
            }
        }
    }

    private fun scanLocalLibrary(): List<Track> {
        val defaultRoots = LocalLibraryScanner.defaultRoots()
        val extra = sources.extraFolders.value.map { File(it) }.filter { it.isDirectory }
        val roots = (defaultRoots + extra).distinctBy { it.absolutePath }
        _scanMessage.value = if (roots.isEmpty()) {
            "No local folders yet"
        } else {
            "Scanning ${roots.joinToString { it.name }}…"
        }
        return if (roots.isEmpty()) emptyList() else LocalLibraryScanner.scan(roots)
    }

    private suspend fun fetchRemote(remote: RemoteAccount, partial: Boolean): List<Track> {
        val known = _scanSources.value.firstOrNull { it.id == remote.id }?.count ?: 0
        val result = when (remote.kind) {
            SourceKind.JELLYFIN -> {
                val authed = if (remote.accessToken.isNullOrBlank()) {
                    jellyfin.authenticate(remote).getOrElse { throw it }
                        .also { sources.upsertRemote(it) }
                } else remote
                if (partial && known > 0) {
                    val total = jellyfin.audioCount(authed).getOrElse { -1 }
                    if (total in 0..known) return emptyList()
                    val cap = if (total > known) (total - known + 64).coerceIn(1, 2_000) else 256
                    jellyfin.listTracks(
                        authed,
                        maxItems = cap,
                        sortBy = "DateCreated",
                        sortOrder = "Descending"
                    )
                } else {
                    jellyfin.listTracks(authed)
                }
            }
            SourceKind.SUBSONIC -> {
                if (partial && known > 0) subsonic.listNewestTracks(remote)
                else subsonic.listTracks(remote)
            }
            SourceKind.LOCAL -> Result.success(emptyList())
        }
        return result.getOrElse { throw it }
    }

    private fun refreshScanSources() {
        val existing = _scanSources.value.associateBy { it.id }
        val local = existing[LOCAL_SCAN_ID]
            ?: DesktopScanSource(LOCAL_SCAN_ID, "Local library", DesktopScanStatus.IDLE)
        val remotes = sources.remotes.value.filter { it.enabled }.map { remote ->
            val prev = existing[remote.id]
            DesktopScanSource(
                id = remote.id,
                name = remote.name,
                status = prev?.status ?: DesktopScanStatus.IDLE,
                detail = prev?.detail.orEmpty(),
                count = prev?.count ?: 0
            )
        }
        _scanSources.value = listOf(local.copy(name = "Local library")) + remotes
    }

    private fun patchScan(
        id: String,
        status: DesktopScanStatus,
        detail: String,
        count: Int? = null
    ) {
        _scanSources.value = _scanSources.value.map {
            if (it.id != id) it
            else it.copy(status = status, detail = detail, count = count ?: it.count)
        }
    }

    private fun markRunning(status: DesktopScanStatus, detail: String) {
        _scanSources.value = _scanSources.value.map {
            if (it.status == DesktopScanStatus.RUNNING) it.copy(status = status, detail = detail)
            else it
        }
    }

    fun extraScan(path: String) = addFolder(path)

    suspend fun searchRemotes(query: String, sourceIds: Set<String>?): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val remotes = sources.remotes.value.filter { it.enabled }
            .filter { sourceIds == null || it.id in sourceIds }
        val out = ArrayList<Track>()
        for (remote in remotes) {
            val result = when (remote.kind) {
                SourceKind.JELLYFIN -> jellyfin.searchTracks(remote, q)
                SourceKind.SUBSONIC -> subsonic.searchTracks(remote, q)
                SourceKind.LOCAL -> continue
            }
            result.onSuccess { out += it }
        }
        return out
    }

    fun release() {
        ticker?.cancel()
        media.release()
        player.release()
        runCatching { http.close() }
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val LOCAL_SCAN_ID = "local"
    }
}
