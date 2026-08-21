package capital.yuri.yuriplayer.desktop.os.win

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.os.OsMediaControls
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.MSG
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Windows media keys (VK_MEDIA_*) plus a hook point for System Media
 * Transport Controls. SMTC's WinRT `GetForCurrentView` needs a CoreWindow
 * we don't have from Compose Desktop; media keys still work globally via
 * RegisterHotKey so the OS keyboard / headset controls the player.
 */
class WindowsSmtcControls : OsMediaControls {
    private val callbacks = AtomicReference<OsMediaControls.Callbacks?>(null)
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null

    override fun attach(callbacks: OsMediaControls.Callbacks) {
        this.callbacks.set(callbacks)
        if (!running.compareAndSet(false, true)) return
        loopThread = thread(name = "yuri-smtc-keys", isDaemon = true) {
            registerAndPump(callbacks)
        }
    }

    override fun update(track: Track?, playing: Boolean, positionMs: Long, durationMs: Long, volume: Float) {
        WindowsNowPlaying.track = track
        WindowsNowPlaying.playing = playing
        WindowsNowPlaying.positionMs = positionMs
        WindowsNowPlaying.durationMs = durationMs
    }

    override fun release() {
        running.set(false)
        runCatching { User32.INSTANCE.PostQuitMessage(0) }
        loopThread = null
        callbacks.set(null)
    }

    private fun registerAndPump(cb: OsMediaControls.Callbacks) {
        val user = User32.INSTANCE
        val mods = 0
        listOf(
            HOT_PLAYPAUSE to VK_MEDIA_PLAY_PAUSE,
            HOT_NEXT to VK_MEDIA_NEXT,
            HOT_PREV to VK_MEDIA_PREV,
            HOT_STOP to VK_MEDIA_STOP
        ).forEach { (id, vk) ->
            user.RegisterHotKey(null, id, mods, vk)
        }
        val msg = MSG()
        while (running.get()) {
            val r = user.GetMessage(msg, null as HWND?, 0, 0)
            if (r == 0) break
            if (msg.message == WinUser.WM_HOTKEY) {
                when (msg.wParam.toInt()) {
                    HOT_PLAYPAUSE -> cb.onPlayPause()
                    HOT_NEXT -> cb.onNext()
                    HOT_PREV -> cb.onPrevious()
                    HOT_STOP -> cb.onStop()
                }
            }
        }
        listOf(HOT_PLAYPAUSE, HOT_NEXT, HOT_PREV, HOT_STOP).forEach {
            user.UnregisterHotKey(null, it)
        }
    }

    companion object {
        private const val VK_MEDIA_NEXT = 0xB0
        private const val VK_MEDIA_PREV = 0xB1
        private const val VK_MEDIA_STOP = 0xB2
        private const val VK_MEDIA_PLAY_PAUSE = 0xB3
        private const val HOT_PLAYPAUSE = 0xA100
        private const val HOT_NEXT = 0xA101
        private const val HOT_PREV = 0xA102
        private const val HOT_STOP = 0xA103
    }
}

internal object WindowsNowPlaying {
    @Volatile var track: Track? = null
    @Volatile var playing: Boolean = false
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
}
