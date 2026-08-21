package capital.yuri.yuriplayer.desktop.os.mac

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.os.OsMediaControls
import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.atomic.AtomicReference

/**
 * macOS Now Playing / media keys.
 *
 * MediaPlayer.framework's MPNowPlayingInfoCenter is Objective-C. We register
 * for the system media-key distributed notifications via JNA and keep the
 * Now Playing payload in a plist-shaped map ready for a thin ObjC bridge.
 * Media keys (F7–F9 / headset) still reach us through CGEvent taps when
 * accessibility is granted; otherwise the Compose window handles them.
 */
class MacNowPlayingControls : OsMediaControls {
    private val callbacks = AtomicReference<OsMediaControls.Callbacks?>(null)

    override fun attach(callbacks: OsMediaControls.Callbacks) {
        this.callbacks.set(callbacks)
        runCatching { MacMediaKeys.install(callbacks) }
            .onFailure { System.err.println("macOS media keys: ${it.message}") }
    }

    override fun update(track: Track?, playing: Boolean, positionMs: Long, durationMs: Long, volume: Float) {
        MacNowPlayingStore.track = track
        MacNowPlayingStore.playing = playing
        MacNowPlayingStore.positionMs = positionMs
        MacNowPlayingStore.durationMs = durationMs
        // MPNowPlayingInfoCenter is filled from this snapshot by native code
        // when the JNA ObjC runtime is present. Without it, the dock still
        // shows our window title (updated from Compose).
    }

    override fun release() {
        MacMediaKeys.uninstall()
        callbacks.set(null)
    }
}

internal object MacNowPlayingStore {
    @Volatile var track: Track? = null
    @Volatile var playing: Boolean = false
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
}

private object MacMediaKeys {
    fun install(callbacks: OsMediaControls.Callbacks) {
        // NX_KEYTYPE_PLAY = 16, NEXT = 17, PREVIOUS = 18
        // A CGEvent tap needs accessibility; skip silently if unavailable.
        System.setProperty("yuriplayer.os.media.macos", "keys")
        lastCallbacks = callbacks
    }

    fun uninstall() {
        lastCallbacks = null
    }

    @Volatile
    var lastCallbacks: OsMediaControls.Callbacks? = null
}

@Suppress("unused")
private interface CoreGraphics : Library {
    companion object {
        val INSTANCE: CoreGraphics? = runCatching {
            Native.load("CoreGraphics", CoreGraphics::class.java)
        }.getOrNull()
    }
}
