package capital.yuri.yuriplayer.desktop.player

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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LibVLC via vlcj. Same job as Android's VlcPlaybackEngine: decode + output.
 * LWJGL/OpenAL is a mixer, not a codec — we are not going there.
 *
 * All native calls run on one worker so the Compose/AWT thread never blocks
 * inside libvlc (start() can deadlock the EDT).
 */
class VlcjPlaybackEngine : PlaybackEngine {
    private val vlc = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yuri-vlc").apply { isDaemon = true }
    }
    private val factory: MediaPlayerFactory?
    private val player: MediaPlayer?
    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()
    private val _isPlaying = MutableStateFlow(false)
    private val _currentUri = MutableStateFlow<String?>(null)
    private val positionMs = AtomicLong(0)
    private val durationMs = AtomicLong(0)
    private val volumePercent = AtomicInteger(100)
    private val pendingStartMs = AtomicLong(-1L)
    private val pendingMedia = AtomicReference<PlaybackMedia?>(null)
    private val preparedNext = AtomicReference<PlaybackMedia?>(null)
    private val ignoreFinishedUntil = AtomicLong(0)
    val nativeError: String?

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    init {
        val discovered = NativeDiscovery().discover()
        val created = runCatching {
            MediaPlayerFactory(
                "--no-video",
                "--no-metadata-network-access",
                "--network-caching=1500",
                "--file-caching=300",
                "--aout=any"
            )
        }
        factory = created.getOrNull()
        player = factory?.mediaPlayers()?.newMediaPlayer()
        nativeError = when {
            factory == null ->
                created.exceptionOrNull()?.message
                    ?: if (!discovered) "LibVLC not found. Install VLC (libvlc) and restart."
                    else "Could not start LibVLC."
            player == null -> "LibVLC opened but could not create a player."
            else -> null
        }
        if (nativeError != null) {
            System.err.println("VlcjPlaybackEngine: $nativeError")
        }
        player?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                val pending = pendingStartMs.getAndSet(-1L)
                if (pending > 0L) {
                    mediaPlayer.controls().setTime(pending)
                    positionMs.set(pending)
                } else {
                    positionMs.set(mediaPlayer.status().time().coerceAtLeast(0L))
                }
                _isPlaying.value = true
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
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
                listeners.forEach { it.onEnded() }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                listeners.forEach { it.onError("VLC failed to play", recoverable = true) }
            }
        })
        onVlc {
            player?.audio()?.setVolume(volumePercent.get())
            player?.audio()?.setMute(false)
        }
    }

    override fun load(current: PlaybackMedia, successor: PlaybackMedia?, startPositionMs: Long) {
        pendingMedia.set(current)
        preparedNext.set(successor)
        pendingStartMs.set(if (startPositionMs > 0) startPositionMs else -1L)
        positionMs.set(startPositionMs.coerceAtLeast(0L))
        _currentUri.value = current.uri
        _isPlaying.value = false
        ignoreFinishedUntil.set(System.currentTimeMillis() + 750)
    }

    override fun play() {
        onVlc {
            val p = player ?: run {
                listeners.forEach { it.onError(nativeError ?: "No audio engine", false) }
                return@onVlc
            }
            val media = pendingMedia.getAndSet(null)
            p.audio().setMute(false)
            p.audio().setVolume(volumePercent.get())
            ignoreFinishedUntil.set(System.currentTimeMillis() + 750)
            if (media != null) {
                val mrl = toMrl(media.uri)
                System.err.println("Vlcj play $mrl")
                val extra = pendingStartMs.get().takeIf { it > 0 }?.let {
                    arrayOf(":start-time=${it / 1000.0}")
                } ?: emptyArray()
                val ok = p.media().play(mrl, *(mediaOptions(media) + extra))
                if (!ok) {
                    listeners.forEach { it.onError("Could not open ${media.title}", recoverable = true) }
                    return@onVlc
                }
                _currentUri.value = media.uri
            } else {
                p.controls().play()
            }
        }
    }

    override fun pause() {
        onVlc {
            val media = pendingMedia.get()
            val p = player ?: return@onVlc
            if (media != null && p.status()?.isPlaying != true) {
                val mrl = toMrl(media.uri)
                p.media().prepare(mrl, *mediaOptions(media))
                p.controls().setPause(true)
            } else {
                p.controls().setPause(true)
            }
        }
    }

    override fun stop() {
        onVlc { player?.controls()?.stop() }
        preparedNext.set(null)
        _currentUri.value = null
        _isPlaying.value = false
        positionMs.set(0)
    }

    override fun seekTo(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        this.positionMs.set(ms)
        pendingStartMs.set(-1L)
        onVlc { player?.controls()?.setTime(ms) }
    }

    override fun getPositionMs(): Long {
        val pending = pendingStartMs.get()
        if (pending > 0L) return pending
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

    override fun hasPreparedNext(): Boolean = preparedNext.get() != null

    override fun playPreparedNext(): Boolean {
        val next = preparedNext.getAndSet(null) ?: return false
        pendingMedia.set(next)
        pendingStartMs.set(-1L)
        play()
        return true
    }

    override fun release() {
        onVlc {
            runCatching { player?.release() }
            runCatching { factory?.release() }
        }
        vlc.shutdown()
        listeners.clear()
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun onVlc(block: () -> Unit) {
        if (vlc.isShutdown) return
        vlc.execute {
            try {
                block()
            } catch (t: Throwable) {
                System.err.println("Vlcj: ${t.message}")
                t.printStackTrace()
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

    private fun mediaOptions(media: PlaybackMedia): Array<String> {
        if (media.headers.isEmpty()) return emptyArray()
        return media.headers.map { (k, v) -> ":http-header=$k: $v" }.toTypedArray()
    }
}
