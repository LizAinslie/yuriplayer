package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * [PlaybackEngine] backed by LibVLC.
 *
 * Local SAF / MediaStore tracks are opened via a file descriptor — LibVLC cannot
 * play Android `content://` URIs as VLC locations. Window is single-item:
 * [capital.yuri.yuriplayer.player.MusicService] advances the queue on [onEnded].
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
            "--audio-time-stretch",
            "--network-caching=3000",
            "--file-caching=1500",
            "--no-video"
        )
    )

    private val mediaPlayer: MediaPlayer = MediaPlayer(libVLC).also { mp ->
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playWhenReady = true
                    _isPlaying.value = true
                    val seek = pendingSeekMs
                    if (seek > 0L) {
                        pendingSeekMs = -1L
                        runCatching { mp.time = seek }
                    }
                    dispatch { onIsPlayingChanged(true) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
                }
                MediaPlayer.Event.Paused -> {
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                }
                MediaPlayer.Event.Stopped -> {
                    // stop() is async; ignore if we already started a new item
                    if (loadGeneration != eventGeneration) return@setEventListener
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.IDLE) }
                }
                MediaPlayer.Event.EndReached -> {
                    if (loadGeneration != eventGeneration) return@setEventListener
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
    private var pendingSeekMs: Long = -1L
    private var loadGeneration: Int = 0
    private var eventGeneration: Int = 0

    /** Kept open for the duration of the current item (content:// FDs). */
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentAfd: AssetFileDescriptor? = null

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
        } else if (mediaPlayer.isPlaying || mediaPlayer.media != null) {
            val length = mediaPlayer.length
            if (length > 0) {
                mediaPlayer.time = positionMs.coerceIn(0L, length)
            } else {
                pendingSeekMs = positionMs.coerceAtLeast(0L)
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
        val t = mediaPlayer.time
        if (t > 0L) return t
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
        // MusicService calls play()/pause() explicitly after setWindow.
    }

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun release() {
        listeners.clear()
        runCatching {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.release()
        }
        closeDescriptors()
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
        loadGeneration += 1
        val gen = loadGeneration
        eventGeneration = gen
        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }
        try {
            val media = buildMedia(item)
            mediaPlayer.media = media
            media.release()
            pendingSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
            if (autoPlay) {
                playWhenReady = true
                mediaPlayer.play()
            }
            dispatch { onMediaTransition(PlaybackEngine.TransitionReason.PLAYLIST) }
            Log.i(TAG, "loadAt idx=$idx uri=${item.uri} scheme=${item.uri.scheme} autoPlay=$autoPlay")
        } catch (e: Exception) {
            Log.e(TAG, "loadAt failed ${item.uri}", e)
            dispatch { onError(e.message ?: "VLC load failed", recoverable = true) }
        }
    }

    private fun buildMedia(item: PlaybackMedia): Media {
        closeDescriptors()
        val uri = item.uri
        val scheme = uri.scheme?.lowercase().orEmpty()
        val media = when {
            scheme == "content" -> mediaFromContent(uri)
            scheme == "file" -> {
                val path = uri.path
                if (!path.isNullOrBlank() && File(path).exists()) Media(libVLC, path)
                else Media(libVLC, uri)
            }
            scheme == "http" || scheme == "https" -> Media(libVLC, uri)
            else -> {
                val path = uri.path
                if (!path.isNullOrBlank() && File(path).canRead()) Media(libVLC, path)
                else Media(libVLC, uri)
            }
        }
        // Audio-only: HW video decoder can error on some FLACs
        media.setHWDecoderEnabled(false, false)
        item.headers.forEach { (k, v) ->
            media.addOption(":http-header=$k: $v")
        }
        if (item.isNetwork) {
            media.addOption(":network-caching=3000")
        }
        return media
    }

    private fun mediaFromContent(uri: Uri): Media {
        val resolver = appContext.contentResolver
        val afd = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (afd != null) {
            currentAfd = afd
            Log.i(TAG, "content AFD uri=$uri offset=${afd.startOffset} len=${afd.length}")
            return Media(libVLC, afd)
        }
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open content uri: $uri")
        currentPfd = pfd
        Log.i(TAG, "content PFD uri=$uri")
        return Media(libVLC, pfd.fileDescriptor)
    }

    private fun closeDescriptors() {
        runCatching { currentAfd?.close() }
        runCatching { currentPfd?.close() }
        currentAfd = null
        currentPfd = null
    }

    private fun stopInternal() {
        loadGeneration += 1
        eventGeneration = loadGeneration
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.media = null }
        closeDescriptors()
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
