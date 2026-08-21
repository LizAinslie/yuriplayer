package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.LocalLibraryScanner
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.core.player.PlayerSession
import capital.yuri.yuriplayer.desktop.os.createOsMediaControls
import capital.yuri.yuriplayer.desktop.player.VlcjPlaybackEngine
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

class DesktopSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val engine = VlcjPlaybackEngine()
    val player = PlayerSession(engine)
    private val media: OsMediaControls = createOsMediaControls()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanMessage = MutableStateFlow("Scanning…")
    val scanMessage: StateFlow<String> = _scanMessage.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    val dirs = appDirectories()

    private var ticker: Job? = null

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
            val roots = LocalLibraryScanner.defaultRoots()
            _scanMessage.value = if (roots.isEmpty()) {
                "No default music folder found"
            } else {
                "Scanning ${roots.joinToString { it.name }}…"
            }
            val found = LocalLibraryScanner.scan(roots)
            _tracks.value = found
            _scanMessage.value = when {
                found.isEmpty() ->
                    "No audio in ${roots.joinToString { it.absolutePath }}"
                else -> "${found.size} tracks"
            }
        }
    }

    fun playTrack(track: Track) {
        val list = _tracks.value
        val i = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.play(if (list.isNotEmpty()) list else listOf(track), i)
    }

    fun extraScan(path: String) {
        scope.launch(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.isDirectory) return@launch
            val extra = LocalLibraryScanner.scan(listOf(dir))
            val merged = (_tracks.value + extra).distinctBy { it.id }
            _tracks.value = merged
            _scanMessage.value = "${merged.size} tracks"
        }
    }

    fun release() {
        ticker?.cancel()
        media.release()
        player.release()
        scope.coroutineContext[Job]?.cancel()
    }
}
