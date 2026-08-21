package capital.yuri.yuriplayer.desktop.player

import capital.yuri.yuriplayer.core.player.PlaybackEngine
import capital.yuri.yuriplayer.core.player.PlaybackMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LibVLC via vlcj. Same role as Android [capital.yuri.yuriplayer.player.engine.VlcPlaybackEngine]:
 * play [current], optionally keep [successor] warmed on a standby player.
 */
class VlcjPlaybackEngine : PlaybackEngine {
    private val factory = MediaPlayerFactory(
        "--no-video",
        "--quiet",
        "--no-metadata-network-access",
        "--network-caching=3000",
        "--file-caching=300"
    )
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()
    private val standby: MediaPlayer = factory.mediaPlayers().newMediaPlayer()
    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()
    private val _isPlaying = MutableStateFlow(false)
    private val _currentUri = MutableStateFlow<String?>(null)
    private val preparedNext = AtomicReference<PlaybackMedia?>(null)
    private val ignoringStandbyEnd = AtomicBoolean(false)

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
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

            override fun finished(mediaPlayer: MediaPlayer) {
                if (playPreparedNext()) {
                    listeners.forEach { it.onAutoAdvanced() }
                } else {
                    _isPlaying.value = false
                    listeners.forEach { it.onEnded() }
                }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                listeners.forEach { it.onError("VLC failed to play", recoverable = true) }
            }
        })
        standby.audio().setMute(true)
        standby.audio().setVolume(0)
    }

    override fun load(current: PlaybackMedia, successor: PlaybackMedia?, startPositionMs: Long) {
        preparedNext.set(null)
        ignoringStandbyEnd.set(true)
        standby.controls().stop()
        val ok = player.media().start(current.uri, *mediaOptions(current))
        if (!ok) {
            listeners.forEach { it.onError("Could not open ${current.title}", recoverable = true) }
            return
        }
        if (startPositionMs > 0) player.controls().setTime(startPositionMs)
        _currentUri.value = current.uri
        if (successor != null) setNext(successor)
    }

    override fun play() {
        player.controls().play()
    }

    override fun pause() {
        player.controls().pause()
    }

    override fun stop() {
        player.controls().stop()
        standby.controls().stop()
        preparedNext.set(null)
        _currentUri.value = null
        _isPlaying.value = false
    }

    override fun seekTo(positionMs: Long) {
        player.controls().setTime(positionMs)
    }

    override fun getPositionMs(): Long = player.status().time()

    override fun getDurationMs(): Long = player.status().length()

    override fun setNext(item: PlaybackMedia?) {
        preparedNext.set(item)
        if (item == null) {
            standby.controls().stop()
            return
        }
        standby.audio().setMute(true)
        standby.media().startPaused(item.uri, *mediaOptions(item))
    }

    override fun hasPreparedNext(): Boolean = preparedNext.get() != null

    override fun playPreparedNext(): Boolean {
        val next = preparedNext.getAndSet(null) ?: return false
        val time = standby.status().time().coerceAtLeast(0L)
        // Swap: start standby (already demuxed) on the audible player by
        // re-using the same MRL — LibVLC Java can't steal a native player
        // mid-buffer, so we jump the main player to the warmed URI.
        player.media().start(next.uri, *mediaOptions(next))
        if (time in 1..5_000) player.controls().setTime(time)
        _currentUri.value = next.uri
        standby.controls().stop()
        return true
    }

    override fun warmupNext() {
        val next = preparedNext.get() ?: return
        if (!standby.status().isPlayable) {
            standby.media().startPaused(next.uri, *mediaOptions(next))
        }
    }

    override fun release() {
        runCatching { player.release() }
        runCatching { standby.release() }
        runCatching { factory.release() }
        listeners.clear()
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun mediaOptions(media: PlaybackMedia): Array<String> {
        val opts = ArrayList<String>(media.headers.size + 1)
        media.headers.forEach { (k, v) ->
            opts += ":http-header=$k: $v"
        }
        return opts.toTypedArray()
    }
}
