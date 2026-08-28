package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.artist.ArtistImageCandidate
import capital.yuri.yuriplayer.core.artist.ArtistInfoClient
import capital.yuri.yuriplayer.core.artist.ArtistProfileStore
import capital.yuri.yuriplayer.core.library.CoverPixels
import capital.yuri.yuriplayer.core.library.LocalLibraryScanner
import capital.yuri.yuriplayer.core.library.indexKeys
import capital.yuri.yuriplayer.core.library.rawSourceId
import capital.yuri.yuriplayer.core.network.NetworkMonitor
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.core.player.PlaybackSnapshot
import capital.yuri.yuriplayer.core.player.RepeatMode
import capital.yuri.yuriplayer.core.source.JellyfinCatalog
import capital.yuri.yuriplayer.core.source.LibrarySourceStore
import capital.yuri.yuriplayer.core.source.RemoteAccount
import capital.yuri.yuriplayer.core.source.SourceKind
import capital.yuri.yuriplayer.core.source.SubsonicCatalog
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.desktop.player.DesktopPlayerHost
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
    val count: Int = 0,
    val startIndex: Int = 0,
    val delivered: Int = 0,
    val totalHint: Int? = null
)

class DesktopSession(
    val player: DesktopPlayerHost,
    private val media: OsMediaControls
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val http = HttpClient(CIO)
    val network: NetworkMonitor = DesktopNetworkMonitor()

    private val _engineMessage = MutableStateFlow<String?>(null)
    val engineMessage: StateFlow<String?> = _engineMessage.asStateFlow()

    private val _tracks = MutableStateFlow<List<Song>>(emptyList())
    val tracks: StateFlow<List<Song>> = _tracks.asStateFlow()

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
    val sourcePrefs = DesktopSourcePrefs(dirs.configDir)
    val sources = LibrarySourceStore(dirs.configDir)
    private val index = DesktopIndexStore(dirs.cacheDir)
    private val playbackStore = DesktopPlaybackStore(dirs.configDir)
    val jellyfin = JellyfinCatalog(http, sources.deviceId)
    val subsonic = SubsonicCatalog(http)
    val artists = ArtistInfoClient(
        http,
        ArtistProfileStore(dirs.configDir, dirs.cacheDir)
    ) { name -> libraryArtistImages(name) }

    private var ticker: Job? = null
    private var persistJob: Job? = null
    private var scanJob: Job? = null
    private var sourceJob: Job? = null
    private val scanGate = Mutex()
    private val stopAll = AtomicBoolean(false)
    private val pauseAll = AtomicBoolean(false)
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppedIds = ConcurrentHashMap.newKeySet<String>()
    private val currentSourceId = AtomicReference<String?>(null)

    var onRaise: (() -> Unit)? = null

    init {
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
            override fun onVolume(value: Float) = player.setVolume(value)
            override fun onLoop(mode: RepeatMode) = player.setRepeat(mode)
            override fun onShuffle(enabled: Boolean) = player.setShuffle(enabled)
            override fun onRaise() { onRaise?.invoke() }
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
                    durationMs = _durationMs.value,
                    volume = player.volume.value
                )
                media.setLoop(player.repeat.value)
                media.setShuffle(player.shuffle.value)
                delay(250)
            }
        }
        scope.launch(Dispatchers.IO) {
            player.current.collect { track ->
                _coverPixels.value = CoverPixels.argb(track?.albumArtUri, track?.path)
            }
        }
        scope.launch {
            sources.remotes.collect { refreshScanSources() }
        }
        player.mediaFor = ::freshMedia
        hydrateIndex()
        restorePlayback()
        media.update(
            track = player.current.value,
            playing = player.isPlaying.value,
            positionMs = player.positionMs(),
            durationMs = player.durationMs(),
            volume = player.volume.value
        )
        media.setLoop(player.repeat.value)
        media.setShuffle(player.shuffle.value)
        startPersist()
    }

    private fun hydrateIndex() {
        val cached = index.loadTracks()
        if (cached.isNotEmpty()) _tracks.value = cached
        val cps = index.loadCheckpoints()
        if (cps.isNotEmpty()) {
            _scanSources.value = cps.map { row ->
                DesktopScanSource(
                    id = row.id,
                    name = row.name,
                    status = runCatching { DesktopScanStatus.valueOf(row.status) }
                        .getOrDefault(DesktopScanStatus.IDLE),
                    detail = row.detail,
                    count = row.count,
                    startIndex = row.startIndex,
                    delivered = row.delivered,
                    totalHint = row.totalHint
                )
            }
        }
        _scanMessage.value = when {
            cached.isEmpty() -> "Indexing…"
            else -> "${cached.size} tracks"
        }
        val snaps = playlists.playlists.value.flatMap { it.snapshots }
        if (snaps.isNotEmpty()) mergeTracks(snaps)
        requestScan(force = cached.isEmpty())
    }

    private fun restorePlayback() {
        val snap = playbackStore.load() ?: return
        player.restore(resolveSnap(snap), play = false)
        _positionMs.value = player.positionMs()
        _durationMs.value = player.durationMs()
    }

    private fun freshMedia(track: Song): Song {
        val remote = sources.remotes.value.firstOrNull { it.id == track.sourceId && it.enabled }
            ?: return track
        val raw = track.rawSourceId() ?: return track
        return when (remote.kind) {
            SourceKind.SUBSONIC -> track.copy(
                contentUri = subsonic.streamUrl(remote, raw),
                albumArtUri = track.albumArtUri ?: subsonic.coverUrl(remote, raw)
            )
            SourceKind.JELLYFIN -> track.copy(contentUri = jellyfin.streamUrl(remote, raw))
            SourceKind.LOCAL -> track
        }
    }

    private fun resolveSnap(snap: PlaybackSnapshot): PlaybackSnapshot {
        val lib = _tracks.value
        if (lib.isEmpty()) return snap
        val byKey = HashMap<String, Song>(lib.size * 2)
        for (t in lib) {
            t.indexKeys().forEach { byKey.putIfAbsent(it, t) }
        }
        fun resolve(list: List<Song>) = list.map { t ->
            t.indexKeys().firstNotNullOfOrNull { byKey[it] } ?: t
        }
        return snap.copy(
            queue = resolve(snap.queue),
            linear = resolve(snap.linear),
            history = resolve(snap.history),
            hotQueue = resolve(snap.hotQueue),
            coldQueue = resolve(snap.coldQueue),
            coldOriginal = resolve(snap.coldOriginal.ifEmpty { snap.linear })
        )
    }

    private fun startPersist() {
        persistJob = scope.launch {
            while (isActive) {
                delay(2_000)
                persistPlayback()
            }
        }
    }

    fun persistPlayback() {
        playbackStore.save(player.snapshot())
    }

    fun playTrack(track: Song) {
        val list = _tracks.value
        val i = list.indexOfFirst { it.songKey == track.songKey }.coerceAtLeast(0)
        player.play(if (list.isNotEmpty()) list else listOf(track), i)
    }

    fun replaceTracks(updated: List<Song>) {
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.songKey }
        _tracks.value = _tracks.value.map { byId[it.songKey] ?: it }
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
        replaceSourceTracks(id, emptyList())
        persistIndex()
        refreshScanSources()
    }

    fun indexFolder(path: String) {
        requestScan(force = true, sourceId = LOCAL_SCAN_ID)
    }

    fun indexRemote(account: RemoteAccount) {
        if (!account.enabled || account.kind == SourceKind.LOCAL) return
        requestScan(force = true, sourceId = account.id)
    }

    private fun mergeTracks(incoming: List<Song>) {
        if (incoming.isEmpty()) return
        val before = _tracks.value.size
        _tracks.value = (_tracks.value + incoming).distinctBy { it.songKey }
        PlaylistLog.index("merge +${incoming.size} $before→${_tracks.value.size}")
    }

    fun ensureTracks(incoming: List<Song>) {
        if (incoming.isEmpty()) return
        PlaylistLog.index("ensure ${incoming.map { "${it.displayTitle}/${it.songKey}" }}")
        mergeTracks(incoming)
        persistIndex()
        playlists.remember(incoming)
    }

    private fun playlistRefs(): Set<String> =
        playlists.playlists.value.flatMap { pl ->
            pl.trackIds + pl.snapshots.flatMap { it.indexKeys() }
        }.toHashSet()

    private fun replaceSourceTracks(sourceId: String, incoming: List<Song>) {
        val referenced = playlistRefs()
        val existing = _tracks.value
        val incomingIds = incoming.map { it.songKey }.toHashSet()
        val orphans = existing.filter { t ->
            (t.sourceId ?: LOCAL_SCAN_ID) == sourceId &&
                t.songKey !in incomingIds &&
                t.indexKeys().any { it in referenced }
        }
        val rest = existing.filterNot { (it.sourceId ?: LOCAL_SCAN_ID) == sourceId }
        val dropped = existing.count {
            (it.sourceId ?: LOCAL_SCAN_ID) == sourceId &&
                it.songKey !in incomingIds &&
                it.indexKeys().none { k -> k in referenced }
        }
        PlaylistLog.index(
            "replace source=$sourceId incoming=${incoming.size} orphans=${orphans.size} dropped=$dropped refs=${referenced.size}"
        )
        _tracks.value = (rest + incoming + orphans).distinctBy { it.songKey }
        val snaps = playlists.playlists.value.flatMap { it.snapshots }
        if (snaps.isNotEmpty()) mergeTracks(snaps)
    }

    private fun persistIndex() {
        index.saveTracks(_tracks.value)
        persistCheckpoints()
    }

    private fun persistCheckpoints() {
        index.saveCheckpoints(
            _scanSources.value.map {
                DesktopIndexStore.StoredCheckpoint(
                    id = it.id,
                    name = it.name,
                    status = it.status.name,
                    startIndex = it.startIndex,
                    delivered = it.delivered,
                    totalHint = it.totalHint,
                    count = it.count,
                    detail = it.detail
                )
            }
        )
    }

    fun rescan() = requestScan(force = true)

    fun requestScan(force: Boolean = false, sourceId: String? = null) {
        if (force && sourceId == null) {
            pausedIds.clear()
            stoppedIds.clear()
            sourceJob?.cancel()
            scanJob?.cancel()
            _scanSources.value = _scanSources.value.map {
                it.copy(startIndex = 0, delivered = 0)
            }
        }
        if (force && sourceId != null) {
            _scanSources.value = _scanSources.value.map {
                if (it.id == sourceId) it.copy(startIndex = 0, delivered = 0) else it
            }
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
            persistIndex()
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
            persistIndex()
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

                val remoteTracks = mutableListOf<Song>()
                for (remote in remotes) {
                    if (halted()) break
                    val got = scanOneSource(remote.id, remote.name, force, onlySourceId) {
                        fetchRemote(remote, force = force)
                    }
                    remoteTracks += got
                }

                if (stopAll.get() || pauseAll.get()) return@coroutineScope

                if (onlySourceId == null && force) {
                    val keepIds = buildSet {
                        if (doLocal) add(LOCAL_SCAN_ID)
                        remotes.forEach { add(it.id) }
                    }
                    val leftovers = _tracks.value.filterNot {
                        (it.sourceId ?: LOCAL_SCAN_ID) in keepIds
                    }
                    _tracks.value = (leftovers + localTracks + remoteTracks).distinctBy { it.songKey }
                } else if (onlySourceId != null && force) {
                    replaceSourceTracks(onlySourceId, localTracks + remoteTracks)
                } else {
                    mergeTracks(localTracks + remoteTracks)
                }
                persistIndex()
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
            _isScanning.value = false
        }
    }

    private fun halted(): Boolean = stopAll.get() || pauseAll.get()

    private fun shouldScan(id: String, force: Boolean, onlySourceId: String?): Boolean {
        if (id in stoppedIds && onlySourceId != id) return false
        if (id in pausedIds && onlySourceId != id && !force) return false
        val src = _scanSources.value.firstOrNull { it.id == id }
        if (force) return true
        if (onlySourceId == id) return true
        if (src?.status == DesktopScanStatus.PAUSED || src?.status == DesktopScanStatus.STOPPED) {
            return true
        }
        if (src?.status == DesktopScanStatus.DONE && src.count > 0) {
            val othersNeedFirst = _scanSources.value.any {
                it.id != id && it.count == 0 &&
                    it.status != DesktopScanStatus.DONE &&
                    it.status != DesktopScanStatus.PAUSED
            }
            return !othersNeedFirst
        }
        return true
    }

    private suspend fun scanOneSource(
        id: String,
        name: String,
        force: Boolean,
        onlySourceId: String?,
        block: suspend () -> List<Song>
    ): List<Song> {
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
                persistIndex()
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

    private fun scanLocalLibrary(): List<Song> {
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

    private suspend fun fetchRemote(remote: RemoteAccount, force: Boolean): List<Song> {
        val src = _scanSources.value.firstOrNull { it.id == remote.id }
        val known = src?.count ?: _tracks.value.count { it.sourceId == remote.id }
        val already = known > 0 && src?.status == DesktopScanStatus.DONE
        val incremental = !force && already
        val resumeFrom = if (force || incremental) 0 else src?.startIndex ?: 0
        val priorDelivered = if (force || incremental) 0 else src?.delivered ?: 0

        return when (remote.kind) {
            SourceKind.JELLYFIN -> {
                val authed = if (remote.accessToken.isNullOrBlank()) {
                    jellyfin.authenticate(remote).getOrElse { throw it }
                        .also { sources.upsertRemote(it) }
                } else remote
                if (incremental) {
                    val total = jellyfin.audioCount(authed).getOrElse { -1 }
                    if (total in 0..known) return emptyList()
                    val cap = if (total > known) (total - known + 64).coerceIn(1, 2_000) else 256
                    jellyfin.listTracks(
                        authed,
                        maxItems = cap,
                        sortBy = "DateCreated",
                        sortOrder = "Descending"
                    ).getOrElse { throw it }
                } else {
                    walkJellyfin(authed, remote, resumeFrom, priorDelivered)
                }
            }
            SourceKind.SUBSONIC -> {
                if (incremental) {
                    subsonic.listNewestTracks(remote).getOrElse { throw it }
                } else {
                    walkSubsonic(remote, resumeFrom, priorDelivered)
                }
            }
            SourceKind.LOCAL -> emptyList()
        }
    }

    private suspend fun walkJellyfin(
        authed: RemoteAccount,
        remote: RemoteAccount,
        startFrom: Int,
        priorDelivered: Int
    ): List<Song> {
        var cursor = startFrom
        var delivered = priorDelivered
        val collected = ArrayList<Song>()
        val result = jellyfin.listTracks(authed, startFrom = startFrom) { page, next, total ->
            collected += page
            cursor = next
            delivered += page.size
            mergeTracks(page)
            patchScan(
                id = remote.id,
                status = DesktopScanStatus.RUNNING,
                detail = if (total != null) "$delivered / $total" else "$delivered",
                count = delivered,
                startIndex = cursor,
                delivered = delivered,
                totalHint = total
            )
            persistCheckpoints()
            if (pauseAll.get() || remote.id in pausedIds) {
                throw CancellationException("paused")
            }
            if (stopAll.get() || remote.id in stoppedIds) {
                throw CancellationException("stopped")
            }
        }
        return result.getOrElse { throw it }.ifEmpty { collected }
    }

    private suspend fun walkSubsonic(
        remote: RemoteAccount,
        startAlbumOffset: Int,
        priorDelivered: Int
    ): List<Song> {
        var cursor = startAlbumOffset
        var delivered = priorDelivered
        val collected = ArrayList<Song>()
        val result = subsonic.listTracks(remote, startAlbumOffset = startAlbumOffset) { page, next, _ ->
            collected += page
            cursor = next
            delivered += page.size
            mergeTracks(page)
            patchScan(
                id = remote.id,
                status = DesktopScanStatus.RUNNING,
                detail = "$delivered",
                count = delivered,
                startIndex = cursor,
                delivered = delivered
            )
            persistCheckpoints()
            if (pauseAll.get() || remote.id in pausedIds) throw CancellationException("paused")
            if (stopAll.get() || remote.id in stoppedIds) throw CancellationException("stopped")
        }
        return result.getOrElse { throw it }.ifEmpty { collected }
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
                count = prev?.count ?: 0,
                startIndex = prev?.startIndex ?: 0,
                delivered = prev?.delivered ?: 0,
                totalHint = prev?.totalHint
            )
        }
        _scanSources.value = listOf(local.copy(name = "Local library")) + remotes
    }

    private fun patchScan(
        id: String,
        status: DesktopScanStatus,
        detail: String,
        count: Int? = null,
        startIndex: Int? = null,
        delivered: Int? = null,
        totalHint: Int? = null
    ) {
        _scanSources.value = _scanSources.value.map {
            if (it.id != id) it
            else it.copy(
                status = status,
                detail = detail,
                count = count ?: it.count,
                startIndex = startIndex ?: it.startIndex,
                delivered = delivered ?: it.delivered,
                totalHint = totalHint ?: it.totalHint
            )
        }
    }

    private fun markRunning(status: DesktopScanStatus, detail: String) {
        _scanSources.value = _scanSources.value.map {
            if (it.status == DesktopScanStatus.RUNNING) it.copy(status = status, detail = detail)
            else it
        }
    }

    fun extraScan(path: String) = addFolder(path)

    private suspend fun libraryArtistImages(name: String): List<ArtistImageCandidate> {
        val out = ArrayList<ArtistImageCandidate>()
        for (remote in sources.remotes.value.filter { it.enabled }) {
            val result = when (remote.kind) {
                SourceKind.JELLYFIN -> jellyfin.searchArtistImages(remote, name)
                SourceKind.SUBSONIC -> subsonic.searchArtistImages(remote, name)
                SourceKind.LOCAL -> continue
            }
            result.onSuccess { out += it }
        }
        return out
    }

    suspend fun searchRemotes(query: String, sourceIds: Set<String>?): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val remotes = sources.remotes.value.filter { it.enabled }
            .filter { sourceIds == null || it.id in sourceIds }
        val out = ArrayList<Song>()
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
        persistJob?.cancel()
        ticker?.cancel()
        persistPlayback()
        media.release()
        player.release()
        runCatching { http.close() }
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val LOCAL_SCAN_ID = "local"
    }
}
