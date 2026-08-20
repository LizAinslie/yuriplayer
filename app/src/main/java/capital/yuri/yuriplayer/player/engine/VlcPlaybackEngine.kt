package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
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
 * LibVLC backend.
 *
 * Open / network I/O happens on a dedicated thread. Playback starts as soon as
 * the FD or HTTP handle is ready — VLC demuxes and prefetches on demand
 * (file-caching for local, network-caching + reconnect for streams).
 */
class VlcPlaybackEngine(
    context: Context
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioThread = HandlerThread("vlc-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()

    private val libVLC: LibVLC = LibVLC(
        appContext,
        arrayListOf(
            "--audio-time-stretch",
            "--no-video",
            // Start quickly; keep enough cache that UI work (open now-playing,
            // add-to-queue) cannot underrun on low-end devices.
            "--file-caching=$FILE_CACHE_MS",
            "--network-caching=$NETWORK_CACHE_MS",
            "--live-caching=$NETWORK_CACHE_MS",
            "--prefetch-buffer-size=$PREFETCH_BYTES"
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
                    if (loadGeneration != eventGeneration) return@setEventListener
                    _isPlaying.value = false
                    dispatch { onIsPlayingChanged(false) }
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.IDLE) }
                }
                MediaPlayer.Event.EndReached -> {
                    if (loadGeneration != eventGeneration) return@setEventListener
                    eventGeneration = -1
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
                    } else {
                        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
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
            loadAt(index, pendingSeekMs.coerceAtLeast(0L), autoPlay = true)
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
        } else if (mediaPlayer.media != null) {
            val length = mediaPlayer.length
            if (length > 0) {
                mediaPlayer.time = positionMs.coerceIn(0L, length)
            } else {
                pendingSeekMs = positionMs.coerceAtLeast(0L)
            }
        } else {
            pendingSeekMs = positionMs.coerceAtLeast(0L)
        }
    }

    override fun seekToNext() {
        if (window.isEmpty()) return
        if (index >= window.lastIndex) return
        index += 1
        loadAt(index, 0L, autoPlay = playWhenReady)
        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.SEEK) }
    }

    override fun prepare() = Unit

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
    }

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun release() {
        listeners.clear()
        loadGeneration += 1
        runCatching {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.release()
        }
        closeDescriptors()
        runCatching { libVLC.release() }
        ioThread.quitSafely()
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
        pendingSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
        if (autoPlay) playWhenReady = true
        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }

        ioHandler.post {
            val opened = runCatching { openInput(item) }
            mainHandler.post {
                if (gen != loadGeneration) {
                    opened.getOrNull()?.close()
                    return@post
                }
                opened.fold(
                    onSuccess = { input -> attachAndMaybePlay(item, input, gen) },
                    onFailure = { e ->
                        Log.e(TAG, "open failed ${item.uri}", e)
                        dispatch { onError(e.message ?: "VLC open failed", recoverable = true) }
                    }
                )
            }
        }
    }

    private fun attachAndMaybePlay(item: PlaybackMedia, input: OpenedInput, gen: Int) {
        if (gen != loadGeneration) {
            input.close()
            return
        }
        closeDescriptors()
        when (input) {
            is OpenedInput.ContentAfd -> currentAfd = input.afd
            is OpenedInput.ContentPfd -> currentPfd = input.pfd
            else -> Unit
        }
        val media = try {
            mediaFromOpened(item, input)
        } catch (e: Exception) {
            input.close()
            Log.e(TAG, "mediaFromOpened ${item.uri}", e)
            dispatch { onError(e.message ?: "VLC media failed", recoverable = true) }
            return
        }
        applyStreamOptions(media, item)
        mediaPlayer.media = media
        media.release()
        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.PLAYLIST) }
        Log.i(
            TAG,
            "attached uri=${item.uri} network=${item.isNetwork} autoPlay=$playWhenReady"
        )
        if (playWhenReady) mediaPlayer.play()
    }

    private fun openInput(item: PlaybackMedia): OpenedInput {
        val uri = item.uri
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when {
            scheme == "content" -> openContent(uri)
            scheme == "file" -> {
                val path = uri.path
                if (!path.isNullOrBlank() && File(path).canRead()) OpenedInput.Path(path)
                else OpenedInput.Remote(uri)
            }
            scheme == "http" || scheme == "https" -> OpenedInput.Remote(uri)
            else -> {
                val path = uri.path
                if (!path.isNullOrBlank() && File(path).canRead()) OpenedInput.Path(path)
                else OpenedInput.Remote(uri)
            }
        }
    }

    private fun openContent(uri: Uri): OpenedInput {
        val resolver = appContext.contentResolver
        val afd = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (afd != null) {
            Log.i(TAG, "opened AFD uri=$uri offset=${afd.startOffset} len=${afd.length}")
            return OpenedInput.ContentAfd(afd)
        }
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open content uri: $uri")
        Log.i(TAG, "opened PFD uri=$uri")
        return OpenedInput.ContentPfd(pfd)
    }

    private fun mediaFromOpened(item: PlaybackMedia, input: OpenedInput): Media = when (input) {
        is OpenedInput.ContentAfd -> Media(libVLC, input.afd)
        is OpenedInput.ContentPfd -> Media(libVLC, input.pfd.fileDescriptor)
        is OpenedInput.Path -> Media(libVLC, input.path)
        is OpenedInput.Remote -> Media(libVLC, item.uri)
    }

    private fun applyStreamOptions(media: Media, item: PlaybackMedia) {
        media.setHWDecoderEnabled(false, false)
        item.headers.forEach { (k, v) ->
            media.addOption(":http-header=$k: $v")
        }
        if (item.isNetwork) {
            media.addOption(":network-caching=$NETWORK_CACHE_MS")
            media.addOption(":http-reconnect")
            media.addOption(":http-continuous")
        } else {
            media.addOption(":file-caching=$FILE_CACHE_MS")
        }
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

    private sealed class OpenedInput {
        class ContentAfd(val afd: AssetFileDescriptor) : OpenedInput()
        class ContentPfd(val pfd: ParcelFileDescriptor) : OpenedInput()
        class Path(val path: String) : OpenedInput()
        class Remote(val uri: Uri) : OpenedInput()

        fun close() {
            when (this) {
                is ContentAfd -> runCatching { afd.close() }
                is ContentPfd -> runCatching { pfd.close() }
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "VlcEngine"
        private const val FILE_CACHE_MS = 1500
        private const val NETWORK_CACHE_MS = 2500
        private const val PREFETCH_BYTES = 2 * 1024 * 1024

        val DESCRIPTOR = PlaybackEngineDescriptor(
            id = "vlc",
            displayName = "LibVLC",
            description = "VLC engine — FLAC, APE, and formats Media3 may reject",
            platforms = setOf("android")
        )
    }
}
