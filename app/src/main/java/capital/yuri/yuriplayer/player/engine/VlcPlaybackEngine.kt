package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import capital.yuri.yuriplayer.core.log.yuriLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * LibVLC backend with a standby player that pre-buffers the next item
 * (local FD or HTTP) so track changes can start immediately.
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
            "--file-caching=$FILE_CACHE_MS",
            "--network-caching=$NETWORK_CACHE_MS",
            "--prefetch-buffer-size=$PREFETCH_BYTES",
            "--http-user-agent=YuriPlayer/0.1"
        )
    )

    private var activeSlot = 0
    private val players: Array<MediaPlayer> = arrayOf(
        MediaPlayer(libVLC),
        MediaPlayer(libVLC)
    )

    private fun active(): MediaPlayer = players[activeSlot]
    private fun standby(): MediaPlayer = players[1 - activeSlot]

    private var window: List<PlaybackMedia> = emptyList()
    private var index: Int = 0
    private var playWhenReady: Boolean = false
    private var pendingSeekMs: Long = -1L
    private var loadGeneration: Int = 0
    private var eventGeneration: Int = 0
    @Volatile private var buffering: Boolean = false
    /** False until this window has actually started audio. Stopped before that is a load, not EOF. */
    @Volatile private var windowHasPlayed: Boolean = false

    private var currentPfd: ParcelFileDescriptor? = null
    private var currentAfd: AssetFileDescriptor? = null
    private var nextPfd: ParcelFileDescriptor? = null
    private var nextAfd: AssetFileDescriptor? = null
    private var currentMedia: Media? = null
    private var nextMedia: Media? = null

    private val prefetcher = StreamPrefetcher.get(appContext)

    private var nextItem: PlaybackMedia? = null
    @Volatile private var nextReady: Boolean = false
    @Volatile private var nextPreparing: Boolean = false
    @Volatile private var nextWarming: Boolean = false
    private var nextGeneration: Int = 0

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    override val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    init {
        players.forEachIndexed { slot, mp ->
            mp.setEventListener { event ->
                if (slot == activeSlot) onActiveEvent(mp, event)
                else onStandbyEvent(mp, event)
            }
        }
    }

    private fun onActiveEvent(mp: MediaPlayer, event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                playWhenReady = true
                buffering = false
                windowHasPlayed = true
                _isPlaying.value = true
                applyPendingSeek(mp)
                dispatch { onIsPlayingChanged(true) }
                dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
            }
            MediaPlayer.Event.Paused -> {
                if (loadGeneration != eventGeneration) return
                _isPlaying.value = false
                dispatch { onIsPlayingChanged(false) }
                // HTTP underruns pause VLC without a user pause. Kick it
                // back if we still intend to play. Never fight an explicit pause.
                if (playWhenReady) {
                    mainHandler.post {
                        if (!playWhenReady) return@post
                        if (loadGeneration != eventGeneration) return@post
                        if (mp !== active()) return@post
                        if (!mp.isPlaying) {
                            log.i { "unexpected pause → resume" }
                            runCatching { mp.play() }
                        }
                    }
                }
            }
            MediaPlayer.Event.Stopped -> {
                if (loadGeneration != eventGeneration) return
                _isPlaying.value = false
                dispatch { onIsPlayingChanged(false) }
                dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.IDLE) }
                if (!playWhenReady) return
                if (!windowHasPlayed) {
                    log.i { "stopped before play — load, not EOF" }
                    return
                }
                if (currentIsLive()) {
                    log.i { "live stopped → reconnect" }
                    runCatching { mp.play() }
                    return
                }
                // HTTP cancel/EOF often Stopped without EndReached. If we still
                // intend to play, treat this as the end of the track.
                if (swapToPreparedNext()) {
                    dispatch { onAutoAdvanced() }
                } else {
                    eventGeneration = -1
                    dispatch { onEnded() }
                }
            }
            MediaPlayer.Event.EndReached -> {
                if (loadGeneration != eventGeneration) return
                if (currentIsLive()) {
                    if (playWhenReady) {
                        log.i { "live EOF → reconnect" }
                        runCatching { mp.play() }
                    }
                    return
                }
                if (swapToPreparedNext()) {
                    dispatch { onAutoAdvanced() }
                    return
                }
                eventGeneration = -1
                buffering = false
                _isPlaying.value = false
                dispatch { onIsPlayingChanged(false) }
                dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.ENDED) }
                dispatch { onEnded() }
            }
            MediaPlayer.Event.EncounteredError -> {
                log.e { "VLC EncounteredError uri=${_currentUri.value}" }
                _isPlaying.value = false
                dispatch { onIsPlayingChanged(false) }
                if (playWhenReady && swapToPreparedNext()) {
                    dispatch { onAutoAdvanced() }
                    return
                }
                dispatch { onError("VLC playback error", recoverable = true) }
            }
            MediaPlayer.Event.Buffering -> {
                buffering = event.buffering < 100f
                if (buffering) {
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }
                } else {
                    dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
                    applyPendingSeek(mp)
                }
            }
            else -> Unit
        }
    }

    private fun onStandbyEvent(mp: MediaPlayer, event: MediaPlayer.Event) {
        if (nextItem == null) return
        when (event.type) {
            MediaPlayer.Event.Playing, MediaPlayer.Event.Buffering -> {
                val buffered = event.type == MediaPlayer.Event.Playing ||
                    event.buffering >= 100f
                if (!buffered) return
                nextReady = true
                log.i { "standby ready '${nextItem?.title}' warming=$nextWarming" }
                // Stay playing at volume 0. Pause kills HTTP (`reading while
                // paused`) and the swapped stream then dies at EOF.
            }
            MediaPlayer.Event.EncounteredError -> {
                log.w { "standby prepare failed '${nextItem?.title}'" }
                nextReady = false
                nextPreparing = false
                nextWarming = false
            }
            else -> Unit
        }
    }

    override fun setWindow(items: List<PlaybackMedia>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) {
            stopInternal()
            window = emptyList()
            index = 0
            _currentUri.value = null
            clearNext()
            return
        }
        window = items
        index = startIndex.coerceIn(0, items.lastIndex)
        loadAt(index, startPositionMs, autoPlay = playWhenReady)
        // Next is prepared by PlaybackEngine.setNext from the host so we
        // don't kick off two HTTP opens for the same item.
    }

    override fun play() {
        playWhenReady = true
        if (window.isEmpty()) return
        if (currentMedia == null) {
            loadAt(index, pendingSeekMs.coerceAtLeast(0L), autoPlay = true)
            return
        }
        active().play()
    }

    override fun pause() {
        playWhenReady = false
        buffering = false
        runCatching { active().pause() }
        _isPlaying.value = false
        dispatch { onIsPlayingChanged(false) }
        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.READY) }
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
            setNext(window.getOrNull(idx + 1))
        } else if (currentMedia != null) {
            val length = active().length
            if (length > 0) {
                active().time = positionMs.coerceIn(0L, length)
            } else {
                pendingSeekMs = positionMs.coerceAtLeast(0L)
            }
        } else {
            pendingSeekMs = positionMs.coerceAtLeast(0L)
        }
    }

    override fun seekToNext() {
        if (playPreparedNext()) return
        if (window.isEmpty()) return
        if (index >= window.lastIndex) return
        index += 1
        loadAt(index, 0L, autoPlay = playWhenReady)
        setNext(window.getOrNull(index + 1))
        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.SEEK) }
    }

    override fun prepare() = Unit

    override fun getPositionMs(): Long {
        val t = active().time
        if (t > 0L) return t
        val len = active().length
        if (len <= 0L) return 0L
        return (active().position * len).toLong().coerceAtLeast(0L)
    }

    override fun getDurationMs(): Long {
        if (currentIsLive()) return 0L
        return active().length.coerceAtLeast(0L)
    }

    override fun getCurrentIndex(): Int = index

    override fun getMediaCount(): Int = window.size

    override fun getUriAt(index: Int): Uri? = window.getOrNull(index)?.uri

    override fun setPlayWhenReady(value: Boolean) {
        playWhenReady = value
    }

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun isBuffering(): Boolean = buffering

    override fun isLive(): Boolean = currentIsLive()

    private fun currentIsLive(): Boolean = window.getOrNull(index)?.live == true

    override fun setNext(item: PlaybackMedia?) {
        if (currentIsLive()) {
            clearNext()
            return
        }
        if (item == null) {
            clearNext()
            return
        }
        prefetcher.start(item)
        if (nextItem?.mediaId == item.mediaId && (nextReady || nextPreparing)) {
            return
        }
        clearNext()
        prepareStandby(prefetcher.cached(item))
    }

    override fun hasPreparedNext(): Boolean =
        nextReady && nextItem != null && nextMedia != null

    override fun preparedNextId(): String? =
        nextItem?.mediaId?.takeIf { nextReady && nextMedia != null }

    override fun playPreparedNext(): Boolean = swapToPreparedNext()

    override fun warmupNext() = Unit

    override fun release() {
        listeners.clear()
        loadGeneration += 1
        nextGeneration += 1
        players.forEach { mp ->
            runCatching {
                mp.setEventListener(null)
                mp.stop()
                mp.media = null
                mp.release()
            }
        }
        currentMedia = null
        nextMedia = null
        prefetcher.retain(emptySet())
        closeDescriptors()
        closeNextDescriptors()
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
        windowHasPlayed = false
        // Ignore Stopped from this halt — it is not end-of-track.
        eventGeneration = -1
        runCatching { active().stop() }
        eventGeneration = gen
        _isPlaying.value = false
        buffering = true
        dispatch { onPlaybackStateChanged(PlaybackEngine.PlaybackState.BUFFERING) }

        if (item.isNetwork) {
            prefetcher.start(item)
        }

        ioHandler.post {
            val playable = prefetcher.cached(item)
            val opened = runCatching { openInput(playable) }
            mainHandler.post {
                if (gen != loadGeneration) {
                    opened.getOrNull()?.close()
                    return@post
                }
                opened.fold(
                    onSuccess = { input -> attachAndMaybePlay(playable, input, gen) },
                    onFailure = { e ->
                        log.e(e) { "open failed ${playable.uri}" }
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
            log.e(e) { "mediaFromOpened ${item.uri}" }
            dispatch { onError(e.message ?: "VLC media failed", recoverable = true) }
            return
        }
        applyStreamOptions(media, item)
        if (gen != loadGeneration) {
            media.release()
            input.close()
            return
        }
        bindMedia(active(), media, isNext = false)
        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.PLAYLIST) }
        log.i { "attached uri=${item.uri} network=${item.isNetwork} autoPlay=$playWhenReady" }
        if (playWhenReady) active().play()
    }

    private fun applyPendingSeek(mp: MediaPlayer) {
        val seek = pendingSeekMs
        if (seek <= 0L) return
        val len = mp.length
        if (len <= 0L) return
        pendingSeekMs = -1L
        val max = (len - 1_000L).coerceAtLeast(0L)
        val target = seek.coerceIn(0L, max)
        log.i { "seek ${seek}ms → ${target}ms of ${len}ms" }
        runCatching { mp.time = target }
    }

    private fun prepareStandby(item: PlaybackMedia) {
        nextGeneration += 1
        val gen = nextGeneration
        nextItem = item
        nextReady = false
        nextPreparing = true
        nextWarming = false
        ioHandler.post {
            val opened = runCatching { openInput(item) }
            mainHandler.post {
                if (gen != nextGeneration) {
                    opened.getOrNull()?.close()
                    return@post
                }
                opened.fold(
                    onSuccess = { input -> attachStandby(item, input, gen) },
                    onFailure = { e ->
                        log.w { "standby open failed ${item.uri}: ${e.message}" }
                        if (gen == nextGeneration) {
                            nextReady = false
                            nextPreparing = false
                        }
                    }
                )
            }
        }
    }

    private fun attachStandby(item: PlaybackMedia, input: OpenedInput, gen: Int) {
        if (gen != nextGeneration) {
            input.close()
            return
        }
        closeNextDescriptors()
        when (input) {
            is OpenedInput.ContentAfd -> nextAfd = input.afd
            is OpenedInput.ContentPfd -> nextPfd = input.pfd
            else -> Unit
        }
        val media = try {
            mediaFromOpened(item, input)
        } catch (e: Exception) {
            input.close()
            log.w(e) { "standby media failed ${item.uri}" }
            nextReady = false
            nextPreparing = false
            return
        }
        applyStreamOptions(media, item)
        if (gen != nextGeneration) {
            media.release()
            input.close()
            return
        }
        val sp = standby()
        runCatching { sp.stop() }
        bindMedia(sp, media, isNext = true)
        log.i { "standby attached '${item.title}' network=${item.isNetwork}" }
        nextPreparing = false
        nextReady = true
    }

    private fun swapToPreparedNext(): Boolean {
        val nxt = nextItem ?: return false
        if (nextMedia == null) return false

        val old = active()
        val np = standby()
        loadGeneration += 1
        val oldAfd = currentAfd
        val oldPfd = currentPfd
        currentAfd = nextAfd
        currentPfd = nextPfd
        nextAfd = null
        nextPfd = null
        currentMedia = nextMedia
        nextMedia = null

        activeSlot = 1 - activeSlot
        window = listOf(nxt)
        index = 0
        _currentUri.value = nxt.uri
        nextItem = null
        nextReady = false
        nextPreparing = false
        nextWarming = false
        nextGeneration += 1
        eventGeneration = loadGeneration
        playWhenReady = true
        windowHasPlayed = false
        runCatching { np.volume = 100 }
        np.play()
        _isPlaying.value = true
        buffering = false
        log.i { "swap → '${nxt.title}'" }

        runCatching { old.stop() }
        runCatching { old.media = null }
        runCatching { oldAfd?.close() }
        runCatching { oldPfd?.close() }

        dispatch { onMediaTransition(PlaybackEngine.TransitionReason.AUTO) }
        return true
    }

    private fun clearNext() {
        nextGeneration += 1
        nextItem = null
        nextReady = false
        nextPreparing = false
        nextWarming = false
        val sp = standby()
        runCatching { sp.stop() }
        unbindMedia(sp, isNext = true)
        closeNextDescriptors()
    }

    private fun openInput(item: PlaybackMedia): OpenedInput {
        prefetcher.fileIfReady(item.mediaId)?.let { f ->
            log.i { "open disk '${item.title}' ${f.length()}B" }
            return OpenedInput.Path(f.absolutePath)
        }
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
            log.i { "opened AFD uri=$uri offset=${afd.startOffset} len=${afd.length}" }
            return OpenedInput.ContentAfd(afd)
        }
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open content uri: $uri")
        log.i { "opened PFD uri=$uri" }
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
            media.addOption(":http-user-agent=YuriPlayer/0.1")
        } else {
            media.addOption(":file-caching=$FILE_CACHE_MS")
        }
    }

    private fun bindMedia(mp: MediaPlayer, media: Media, isNext: Boolean) {
        unbindMedia(mp, isNext)
        mp.media = media
        // Drop the constructor retain. MediaPlayer.retain()'d it; keeping the
        // Java field without this is what finalized Media with native refs.
        runCatching { media.release() }
        if (isNext) nextMedia = media else currentMedia = media
    }

    private fun unbindMedia(mp: MediaPlayer, isNext: Boolean) {
        // Setter only — never read mp.media (getter retain()s and we never release).
        runCatching { mp.media = null }
        if (isNext) nextMedia = null else currentMedia = null
    }

    private fun closeDescriptors() {
        runCatching { currentAfd?.close() }
        runCatching { currentPfd?.close() }
        currentAfd = null
        currentPfd = null
    }

    private fun closeNextDescriptors() {
        runCatching { nextAfd?.close() }
        runCatching { nextPfd?.close() }
        nextAfd = null
        nextPfd = null
    }

    private fun stopInternal() {
        loadGeneration += 1
        eventGeneration = loadGeneration
        runCatching { active().stop() }
        unbindMedia(active(), isNext = false)
        closeDescriptors()
        clearNext()
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
        private val log = yuriLog("VlcEngine")
        private const val FILE_CACHE_MS = 800
        private const val NETWORK_CACHE_MS = 6_000
        private const val PREFETCH_BYTES = 16 * 1024 * 1024

        val DESCRIPTOR = PlaybackEngineDescriptor(
            id = "vlc",
            displayName = "LibVLC",
            description = "VLC engine — FLAC, APE, and formats Media3 may reject",
            platforms = setOf("android")
        )
    }
}
