package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Routes each [setWindow] to the best backend:
 * - **Local** (file / content / non-http) → [VlcPlaybackEngine] (FLAC-safe)
 * - **Network** (http/https) → [Media3PlaybackEngine]
 *
 * Only one backend is active at a time; the other is paused/stopped.
 */
class HybridPlaybackEngine(
    context: Context
) : PlaybackEngine {

    private val media3 = Media3PlaybackEngine(context)
    private val vlc = VlcPlaybackEngine(context)

    private var active: PlaybackEngine = media3
    private var window: List<PlaybackMedia> = emptyList()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    override val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    private val fanOut = object : PlaybackEngine.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            outerListeners.forEach { it.onIsPlayingChanged(playing) }
        }

        override fun onMediaTransition(reason: PlaybackEngine.TransitionReason) {
            _currentUri.value = active.currentUri.value
            outerListeners.forEach { it.onMediaTransition(reason) }
        }

        override fun onEnded() {
            outerListeners.forEach { it.onEnded() }
        }

        override fun onError(message: String, recoverable: Boolean) {
            outerListeners.forEach { it.onError(message, recoverable) }
        }

        override fun onPlaybackStateChanged(state: PlaybackEngine.PlaybackState) {
            outerListeners.forEach { it.onPlaybackStateChanged(state) }
        }
    }

    private val outerListeners = linkedSetOf<PlaybackEngine.Listener>()

    init {
        media3.addListener(fanOut)
        vlc.addListener(fanOut)
    }

    override fun setWindow(items: List<PlaybackMedia>, startIndex: Int, startPositionMs: Long) {
        window = items
        if (items.isEmpty()) {
            media3.setWindow(emptyList())
            vlc.setWindow(emptyList())
            _currentUri.value = null
            return
        }
        val idx = startIndex.coerceIn(0, items.lastIndex)
        val primary = items[idx]
        val useVlc = shouldUseVlc(primary)
        val next = if (useVlc) vlc else media3
        val other = if (useVlc) media3 else vlc
        if (next !== active) {
            other.pause()
            other.stop()
            active = next
            Log.i(TAG, "switch → ${if (useVlc) "vlc" else "media3"} uri=${primary.uri}")
        }
        active.setWindow(items, idx, startPositionMs)
        _currentUri.value = primary.uri
    }

    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun stop() = active.stop()
    override fun seekTo(index: Int, positionMs: Long) = active.seekTo(index, positionMs)
    override fun seekToNext() = active.seekToNext()
    override fun prepare() = active.prepare()
    override fun getPositionMs(): Long = active.getPositionMs()
    override fun getDurationMs(): Long = active.getDurationMs()
    override fun getCurrentIndex(): Int = active.getCurrentIndex()
    override fun getMediaCount(): Int = active.getMediaCount()
    override fun getUriAt(index: Int): Uri? = active.getUriAt(index)
    override fun setPlayWhenReady(value: Boolean) = active.setPlayWhenReady(value)
    override fun getPlayWhenReady(): Boolean = active.getPlayWhenReady()

    override fun release() {
        outerListeners.clear()
        media3.release()
        vlc.release()
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        outerListeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        outerListeners -= listener
    }

    /** Expose Media3 player only when that backend is active (legacy session helpers). */
    fun media3OrNull(): Media3PlaybackEngine? =
        if (active === media3) media3 else null

    companion object {
        private const val TAG = "HybridEngine"

        val DESCRIPTOR = PlaybackEngineDescriptor(
            id = "hybrid",
            displayName = "Hybrid (VLC local + Media3 stream)",
            description = "LibVLC for on-device files, Media3 for HTTP libraries",
            platforms = setOf("android")
        )

        fun shouldUseVlc(item: PlaybackMedia): Boolean {
            if (item.isNetwork) return false
            val scheme = item.uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            // Local file / content / absolute path → VLC (FLAC-safe)
            return true
        }
    }
}
