package capital.yuri.yuriplayer.desktop.player

import capital.yuri.yuriplayer.core.log.redactSecrets
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.core.player.PlaybackEngine
import capital.yuri.yuriplayer.core.player.PlaybackMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LibVLC via vlcj. Same job as Android's VlcPlaybackEngine: decode + output.
 */
class VlcjPlaybackEngine : PlaybackEngine {
    private val log = yuriLog("Vlcj")
    private val vlc = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yuri-vlc").apply { isDaemon = true }
    }
    private val factory: MediaPlayerFactory?
    private var player: MediaPlayer?
    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()
    private val _isPlaying = MutableStateFlow(false)
    private val _currentUri = MutableStateFlow<String?>(null)
    private val positionMs = AtomicLong(0)
    private val durationMs = AtomicLong(0)
    private val volumePercent = AtomicInteger(100)
    private val pendingStartMs = AtomicLong(-1L)
    private val loadedMedia = AtomicReference<PlaybackMedia?>(null)
    private val preparedNext = AtomicReference<PlaybackMedia?>(null)
    private val openedMrl = AtomicReference<String?>(null)
    private val ignoreFinishedUntil = AtomicLong(0)
    private val recreating = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    val nativeError: String?

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    init {
        val discovered = NativeDiscovery().discover()
        var created: Result<MediaPlayerFactory> = Result.failure(IllegalStateException("not attempted"))
        repeat(FACTORY_RETRIES) { attempt ->
            created = runCatching { MediaPlayerFactory(*FACTORY_ARGS) }
            if (created.isSuccess) return@repeat
            if (attempt < FACTORY_RETRIES - 1) {
                Thread.sleep(FACTORY_RETRY_DELAY_MS)
            }
        }
        factory = created.getOrNull()
        player = factory?.mediaPlayers()?.newMediaPlayer()
        nativeError = when {
            factory == null -> {
                val cause = created.exceptionOrNull()
                val detail = cause?.message ?: cause?.cause?.message
                when {
                    detail != null -> "LibVLC failed to start: $detail"
                    !discovered -> "LibVLC not found. Install VLC (libvlc) and restart."
                    else -> "Could not start LibVLC."
                }
            }
            player == null -> "LibVLC opened but could not create a player."
            else -> null
        }
        if (nativeError != null) {
            log.e { nativeError }
        } else {
            log.i { "libvlc ready" }
        }
        bindEvents(player)
        onVlc {
            player?.audio()?.setVolume(volumePercent.get())
            player?.audio()?.setMute(false)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { release() }
        })
    }

    override fun load(current: PlaybackMedia, successor: PlaybackMedia?, startPositionMs: Long) {
        loadedMedia.set(current)
        preparedNext.set(successor)
        pendingStartMs.set(if (startPositionMs > 0) startPositionMs else -1L)
        positionMs.set(startPositionMs.coerceAtLeast(0L))
        _currentUri.value = current.uri
        _isPlaying.value = false
        openedMrl.set(null)
    }

    override fun play() {
        val opened = openedMrl.get()
        val loaded = loadedMedia.get()
        if (opened != null && loaded != null && toMrl(loaded.uri) == opened) {
            onVlc {
                player?.audio()?.setMute(false)
                player?.controls()?.play()
            }
            return
        }
        val media = loaded
        if (media == null) {
            onVlc {
                player?.audio()?.setMute(false)
                player?.controls()?.play()
            }
            return
        }
        start(media)
    }

    override fun pause() {
        if (openedMrl.get() == null) return
        onVlc { player?.controls()?.setPause(true) }
    }

    override fun stop() {
        onVlc { silentStop() }
        preparedNext.set(null)
        _currentUri.value = null
        _isPlaying.value = false
        positionMs.set(0)
        openedMrl.set(null)
    }

    override fun seekTo(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        log.i { "seek $ms (dur=${durationMs.get()})" }
        this.positionMs.set(ms)
        pendingStartMs.set(-1L)
        onVlc { player?.controls()?.setTime(ms) }
    }

    override fun getPositionMs(): Long {
        val pending = pendingStartMs.get()
        if (pending > 0L && !_isPlaying.value) return pending
        // Poll VLC's actual time rather than trusting only the `timeChanged`
        // event (which is flaky on some distro VLC builds). Called by the ticker
        // every ~250ms, so this keeps position authoritative and seekable.
        val p = player
        if (p != null && openedMrl.get() != null) {
            runCatching { p.status().time() }
                .getOrNull()
                ?.takeIf { it > 0L }
                ?.let { positionMs.set(it) }
        }
        return positionMs.get()
    }

    override fun getDurationMs(): Long = durationMs.get()

    override fun setVolume(percent: Int) {
        volumePercent.set(percent.coerceIn(0, 100))
        onVlc { player?.audio()?.setVolume(volumePercent.get()) }
    }

    override fun getVolume(): Int = volumePercent.get()

    override fun setNext(item: PlaybackMedia?) {
        preparedNext.set(item)
    }

    override fun hasPreparedNext(): Boolean = false

    override fun playPreparedNext(): Boolean = false

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        onVlc {
            runCatching { player?.controls()?.stop() }
            runCatching { factory?.release() }
        }
        vlc.shutdown()
        listeners.clear()
        player = null
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun start(media: PlaybackMedia) {
        onVlc {
            val p = player ?: run {
                listeners.forEach { it.onError(nativeError ?: "No audio engine", false) }
                return@onVlc
            }
            val mrl = toMrl(media.uri)
            val isHttp = mrl.startsWith("http")
            log.i { "play ${redactSecrets(mrl)}" }
            ignoreFinishedUntil.set(System.currentTimeMillis() + 1_200)
            silentStop()
            p.audio().setMute(false)
            p.audio().setVolume(volumePercent.get())
            val pending = if (isHttp) {
                pendingStartMs.set(-1L)
                -1L
            } else {
                pendingStartMs.get()
            }
            val extra = buildList {
                addAll(mediaOptions(media))
                if (pending > 0L) {
                    add(":start-time=${pending / 1000.0}")
                }
                if (isHttp) {
                    add(":http-reconnect")
                    add(":http-user-agent=YuriPlayer/1.0")
                }
            }.toTypedArray()
            val ok = p.media().play(mrl, *extra)
            log.i { "play result ok=$ok ${redactSecrets(mrl)}" }
            if (!ok) {
                log.e { "play failed (ok=false): ${redactSecrets(mrl)}" }
                listeners.forEach { it.onError("Could not open ${media.title}", recoverable = true) }
                return@onVlc
            }
            openedMrl.set(mrl)
            _currentUri.value = media.uri
            p.controls().play()
        }
    }

    private fun silentStop() {
        val until = ignoreFinishedUntil.get()
        ignoreFinishedUntil.set(System.currentTimeMillis() + 1_200)
        runCatching { player?.controls()?.stop() }
        ignoreFinishedUntil.set(maxOf(until, System.currentTimeMillis() + 400))
    }

    private fun onVlc(block: () -> Unit) {
        if (vlc.isShutdown) return
        vlc.execute {
            try {
                block()
            } catch (t: Throwable) {
                log.e(t) { "native call failed" }
                listeners.forEach { it.onError(t.message ?: "VLC error", true) }
            }
        }
    }

    private fun toMrl(uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            when {
                uri.startsWith("file:") -> File(URI(uri)).absolutePath
                else -> File(uri).absolutePath
            }
        } catch (_: Exception) {
            uri
        }
    }

    private fun bindEvents(target: MediaPlayer?) {
        target?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                val pending = pendingStartMs.getAndSet(-1L)
                if (pending > 0L) {
                    mediaPlayer.controls().setTime(pending)
                    positionMs.set(pending)
                }
                // Seed duration from status if lengthChanged hasn't fired yet.
                if (durationMs.get() <= 0L) {
                    runCatching { mediaPlayer.status().length() }
                        .getOrNull()
                        ?.takeIf { it > 0L }
                        ?.let { durationMs.set(it) }
                }
                _isPlaying.value = true
                log.d { "playing pos=${positionMs.get()} dur=${durationMs.get()}" }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                log.d { "paused" }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                log.d { "stopped" }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                positionMs.set(newTime)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                durationMs.set(newLength)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (System.currentTimeMillis() < ignoreFinishedUntil.get()) return
                _isPlaying.value = false
                log.i { "finished ${redactSecrets(_currentUri.value.orEmpty())}" }
                listeners.forEach { it.onEnded() }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                log.e { "error ${redactSecrets(_currentUri.value.orEmpty())}" }
                recyclePlayer()
                listeners.forEach { it.onError("VLC failed to play", recoverable = true) }
            }
        })
    }

    private fun recyclePlayer() {
        if (!recreating.compareAndSet(false, true)) return
        onVlc {
            try {
                val old = player
                runCatching { old?.controls()?.stop() }
                val next = factory?.mediaPlayers()?.newMediaPlayer()
                player = next
                bindEvents(next)
                next?.audio()?.setVolume(volumePercent.get())
                next?.audio()?.setMute(false)
                openedMrl.set(null)
                log.w { "recycled media player after stream error" }
            } finally {
                recreating.set(false)
            }
        }
    }

    private fun mediaOptions(media: PlaybackMedia): Array<String> {
        if (media.headers.isEmpty()) return emptyArray()
        return media.headers.map { (k, v) -> ":http-header=$k: $v" }.toTypedArray()
    }

    companion object {
        private val FACTORY_ARGS = arrayOf(
            "--no-video",
            "--no-metadata-network-access",
            "--network-caching=4000",
            "--file-caching=300",
            "--http-reconnect",
            "--http-user-agent=YuriPlayer/1.0",
            "--aout=any"
        )
        private const val FACTORY_RETRIES = 3
        private const val FACTORY_RETRY_DELAY_MS = 300L
    }
}
