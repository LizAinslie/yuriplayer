package capital.yuri.yuriplayer.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Facade over [MusicService].
 *
 * Lifecycle model:
 * - [bind] starts the service (so it survives Activity onStop) and binds for commands
 * - [play] promotes it to a foreground media service via startForegroundService
 * - [unbind] only drops the Activity connection; playback keeps running
 */
class PlayerController(private val context: Context) {

    private var service: MusicService? = null
    private var bound = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicService.LocalBinder ?: return
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
        val intent = Intent(context, MusicService::class.java)
        // startService keeps the service alive after the last client unbinds.
        // (BIND_AUTO_CREATE alone would destroy it when MainActivity unbinds.)
        context.startService(intent)
        if (!bound) {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Already unbound
        }
        bound = false
        // Intentionally keep [service] reference nullled but do NOT stop the service.
        service = null
        _isConnected.value = false
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        ensureServiceStarted()
        service?.setPlaylist(songs, startIndex)
    }

    fun play() {
        // Promote to foreground so Android allows ongoing playback with a notification.
        // MediaSessionService will call startForeground once the player is playing.
        ContextCompat.startForegroundService(
            context,
            Intent(context, MusicService::class.java)
        )
        service?.play()
    }

    fun pause() = service?.pause()

    fun togglePlayPause() {
        if (service?.isPlaying() == true) {
            service?.pause()
        } else {
            play()
        }
    }

    fun skipToNext() {
        ensureServiceStarted()
        service?.skipToNext()
    }

    fun skipToPrevious() {
        ensureServiceStarted()
        service?.skipToPrevious()
    }

    fun isPlayingNow(): Boolean = service?.isPlaying() == true
    fun getCurrentSong(): Song? = service?.getCurrentSong()

    private fun ensureServiceStarted() {
        context.startService(Intent(context, MusicService::class.java))
        if (!bound) {
            context.bindService(
                Intent(context, MusicService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }
}
