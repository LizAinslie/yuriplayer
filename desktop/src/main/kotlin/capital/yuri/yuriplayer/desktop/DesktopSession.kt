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
import java.io.File
import java.util.UUID

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
        rescan()
    }

    fun removeFolder(path: String) {
        sources.removeFolder(path)
        rescan()
    }

    fun addJellyfin(name: String, baseUrl: String, username: String, password: String) {
        scope.launch(Dispatchers.IO) {
            _scanMessage.value = "Signing in to Jellyfin…"
            val seed = RemoteAccount(
                id = UUID.randomUUID().toString(),
                kind = SourceKind.JELLYFIN,
                name = name.ifBlank { "Jellyfin" },
                baseUrl = baseUrl,
                username = username,
                secret = password
            )
            jellyfin.authenticate(seed)
                .onSuccess {
                    sources.upsertRemote(it)
                    rescan()
                }
                .onFailure { _scanMessage.value = "Jellyfin: ${it.message}" }
        }
    }

    fun addSubsonic(name: String, baseUrl: String, username: String, password: String) {
        scope.launch(Dispatchers.IO) {
            _scanMessage.value = "Signing in to Subsonic…"
            val seed = RemoteAccount(
                id = UUID.randomUUID().toString(),
                kind = SourceKind.SUBSONIC,
                name = name.ifBlank { "Subsonic" },
                baseUrl = baseUrl,
                username = username,
                secret = password
            )
            subsonic.ping(seed)
                .onSuccess {
                    sources.upsertRemote(it)
                    rescan()
                }
                .onFailure { _scanMessage.value = "Subsonic: ${it.message}" }
        }
    }

    fun removeRemote(id: String) {
        sources.removeRemote(id)
        rescan()
    }

    fun rescan() {
        scope.launch(Dispatchers.IO) {
            val defaultRoots = LocalLibraryScanner.defaultRoots()
            val extra = sources.extraFolders.value.map { File(it) }.filter { it.isDirectory }
            val roots = (defaultRoots + extra).distinctBy { it.absolutePath }
            _scanMessage.value = if (roots.isEmpty()) {
                "No local folders yet"
            } else {
                "Scanning ${roots.joinToString { it.name }}…"
            }
            val local = if (roots.isEmpty()) emptyList() else LocalLibraryScanner.scan(roots)
            val remoteTracks = mutableListOf<Track>()
            for (remote in sources.remotes.value.filter { it.enabled }) {
                _scanMessage.value = "Indexing ${remote.name}…"
                val result = when (remote.kind) {
                    SourceKind.JELLYFIN -> {
                        val authed = if (remote.accessToken.isNullOrBlank()) {
                            jellyfin.authenticate(remote).getOrElse {
                                _scanMessage.value = "${remote.name}: ${it.message}"
                                continue
                            }.also { sources.upsertRemote(it) }
                        } else remote
                        jellyfin.listTracks(authed)
                    }
                    SourceKind.SUBSONIC -> subsonic.listTracks(remote)
                    SourceKind.LOCAL -> continue
                }
                result
                    .onSuccess { remoteTracks += it }
                    .onFailure { _scanMessage.value = "${remote.name}: ${it.message}" }
            }
            val merged = (local + remoteTracks).distinctBy { it.id }
            _tracks.value = merged
            _scanMessage.value = when {
                merged.isEmpty() -> "No tracks yet — add a folder or a server in Settings → Library"
                else -> "${merged.size} tracks"
            }
        }
    }

    fun extraScan(path: String) = addFolder(path)

    fun release() {
        ticker?.cancel()
        media.release()
        player.release()
        runCatching { http.close() }
        scope.coroutineContext[Job]?.cancel()
    }
}
