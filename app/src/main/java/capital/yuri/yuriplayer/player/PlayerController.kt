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
 * Thin facade over MusicService so UI / ViewModels never talk to the service directly.
 * Bound once from the Application / Activity lifecycle.
 */
class PlayerController(private val context: Context) {

    private var service: MusicService? = null
    private var bound = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as MusicService.LocalBinder
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
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        context.unbindService(connection)
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

    fun isPlaying(): Boolean = service?.isPlaying() == true
    fun getCurrentSong(): Song? = service?.getCurrentSong()
}
