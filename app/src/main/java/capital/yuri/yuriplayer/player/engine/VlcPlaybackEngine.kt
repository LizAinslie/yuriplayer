package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * [PlaybackEngine] backed by LibVLC.
 *
 * Handles local FLAC / APE / odd containers and HTTP streams that Media3
 * refuses. Window is single-item: [MusicService] advances the queue on [onEnded].
 */
class VlcPlaybackEngine(
    context: Context
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()

    private val libVLC: LibVLC = LibVLC(
        appContext,
        arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            "--network-caching=3000",
            "--file-caching=1500",
            "--no-video",
            "--quiet"
        )
    )

    private val mediaPlayer: MediaPlayer = MediaPlayer(libVLC).also { mp ->
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playWhenReady = true
                    _isPlaying.value = true
                    dispatch { onIsPlayingChanged(true) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
                }
                MediaPlayer.Event.Paused -> {
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                }
                MediaPlayer.Event.Stopped -> {
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.IDLE) }
                }
                MediaPlayer.Event.EndReached -> {
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.ENDED) }
                    dispatch { onEnded() }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC EncounteredError uri=${_currentUri.value}")
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                    dispatch { onError("VLC playback error", recoverable = true) }
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f) {
                        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }
                    }
                }
                else -> Unit
            }
        }
    }

    private var window: List<PlaybackMedia> = emptyList()
    private var index: Int = 0
    private var playWhenReady: Boolean = false

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    override val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    override fun setWindow(items: List<PlaybackMedia>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) {
            stopInternal()
            window = emptyList()
            index = 0
            _currentUri.value = null
            return
        }
        window = items
        index = startIndex.coerceIn(0, items.lastIndex)
        loadAt(index, startPositionMs, autoPlay = playWhenReady)
    }

    override fun play() {
        playWhenReady = true
        if (window.isEmpty()) return
        if (mediaPlayer.media == null) {
            loadAt(index, 0L, autoPlay = true)
            return
        }
        mediaPlayer.play()
    }

    override fun pause() {
        playWhenReady = false
        if (mediaPlayer.isPlaying) mediaPlayer.pause()
    }

    override fun stop() {
        playWhenReady = false
        stopInternal()
    }

    override fun seekTo(index: Int, positionMs: Long) {
        if (window.isEmpty()) return
        val idx = index.coerceIn(0, window.lastIndex)
        if (idx != this.index) {
            this.index = idx
            loadAt(idx, positionMs, autoPlay = playWhenReady)
        } else {
            val length = mediaPlayer.length
            if (length > 0) {
                val fraction = (positionMs.toDouble() / length.toDouble()).coerceIn(0.0, 1.0)
                mediaPlayer.position = fraction.toFloat()
            }
        }
    }

    override fun seekToNext() {
        if (window.isEmpty()) return
        if (index >= window.lastIndex) return
        index += 1
        loadAt(index, 0L, autoPlay = playWhenReady)
        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.SEEK) }
    }

    override fun prepare() {
        // LibVLC prepares as part of setMedia / play
    }

    override fun getPositionMs(): Long {
        val len = mediaPlayer.length
        if (len <= 0L) return 0L
        return (mediaPlayer.position * len).toLong().coerceAtLeast(0L)
    }

    override fun getDurationMs(): Long = mediaPlayer.length.coerceAtLeast(0L)

    override fun getCurrentIndex(): Int = index

    override fun getMediaCount(): Int = window.size

    override fun getUriAt(index: Int): Uri? = window.getOrNull(index)?.uri

    override fun setPlayWhenReady(value: Boolean) {
        playWhenReady = value
        if (value) play() else pause()
    }

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun release() {
        listeners.clear()
        runCatching {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
        runCatching { libVLC.release() }
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun loadAt(idx: Int, startPositionMs: Long, autoPlay: Boolean) {
        val item = window.getOrNull(idx) ?: return
        index = idx
        _currentUri.value = item.uri
        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }
        try {
            mediaPlayer.stop()
            val media = buildMedia(item)
            mediaPlayer.media = media
            media.release() // MediaPlayer retains its own ref
            if (startPositionMs > 0L) {
                // Position is set after playing starts; store and apply on Playing if needed
                mediaPlayer.time = startPositionMs
            }
            if (autoPlay) {
                playWhenReady = true
                mediaPlayer.play()
            }
            dispatch { onMediaTransition(PlaybackEngine.TransitionReason.PLAYLIST) }
            Log.i(TAG, "loadAt idx=$idx uri=${item.uri} autoPlay=$autoPlay")
        } catch (e: Exception) {
            Log.e(TAG, "loadAt failed ${item.uri}", e)
            dispatch { onError(e.message ?: "VLC load failed", recoverable = true) }
        }
    }

    private fun buildMedia(item: PlaybackMedia): Media {
        val media = Media(libVLC, item.uri)
        media.setHWDecoderEnabled(true, false)
        item.headers.forEach { (k, v) ->
            media.addOption(":http-header=$k: $v")
        }
        if (item.isNetwork) {
            media.addOption(":network-caching=3000")
        }
        return media
    }

    private fun stopInternal() {
        runCatching {
            mediaPlayer.stop()
            mediaPlayer.media = null
        }
        _isPlaying.value = false
    }

    private inline fun dispatch(crossinline block: PlaybackEngine.Listener.() -> Unit) {
        val copy = listeners.toList()
        mainHandler.post {
            copy.forEach { runCatching { it.block() } }
        }
    }

    companion object {
        private const val TAG = "VlcEngine"

        val DESCRIPTOR = PlaybackEngineDescriptor(
            id = "vlc",
            displayName = "LibVLC",
            description = "VLC engine — FLAC, APE, and formats Media3 may reject",
            platforms = setOf("android")
        )
    }
}
