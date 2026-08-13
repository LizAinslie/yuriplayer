package capital.yuri.yuriplayer.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Facade over [MusicService].
 *
 * Important: we only *bind* the service here. We do NOT call
 * startForegroundService() up front — that caused ANRs on Android 8+
 * because MediaSessionService only goes foreground once playback starts.
 * Media3 promotes the service to foreground automatically when playing.
 */
class PlayerController(private val context: Context) {

    private var service: MusicService? = null
    private var bound = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val nowPlaying: StateFlow<Song?>
        get() = service?.nowPlaying ?: MutableStateFlow(null)

    val isPlaying: StateFlow<Boolean>
        get() = service?.isPlaying ?: MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicService.LocalBinder
            if (local == null) {
                // Wrong binder type — ignore rather than crash
                return
            }
            service = local.getService()
            bound = true
            _isConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _isConnected.value = false
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(context, MusicService::class.java)
        // BIND_AUTO_CREATE starts the service without the foreground deadline
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Already unbound
        }
        bound = false
        service = null
        _isConnected.value = false
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        service?.setPlaylist(songs, startIndex)
    }

    fun play() = service?.play()
    fun pause() = service?.pause()
    fun togglePlayPause() = service?.togglePlayPause()
    fun skipToNext() = service?.skipToNext()
    fun skipToPrevious() = service?.skipToPrevious()

    fun isPlayingNow(): Boolean = service?.isPlaying() == true
    fun getCurrentSong(): Song? = service?.getCurrentSong()
}
