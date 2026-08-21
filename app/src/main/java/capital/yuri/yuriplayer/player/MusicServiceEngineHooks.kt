package capital.yuri.yuriplayer.player

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song

/**
 * Holds the user-selected [MusicServiceLocalEngine] for [MusicService].
 *
 * When fully wired, **all** playback (local + remote) goes through this host;
 * MediaSession is [capital.yuri.yuriplayer.player.engine.EngineSessionBridge],
 * independent of Media3.
 */
internal class MusicServiceEngineHooks(
    context: Context,
    settings: LibrarySettings,
    sessionActivity: PendingIntent,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onEnded: () -> Unit,
    onAutoAdvanced: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    onError: (String, Boolean) -> Unit = { _, _ -> }
) {
    private val host = MusicServiceLocalEngine(
        context = context,
        settings = settings,
        sessionActivity = sessionActivity,
        onPlay = onPlay,
        onPause = onPause,
        onNext = onNext,
        onPrev = onPrev,
        onSeek = onSeek,
        ended = onEnded,
        autoAdvanced = onAutoAdvanced,
        playingChanged = onPlayingChanged,
        onEngineError = onError
    )

    val engineId get() = host.engineId

    /** True once [playWindow] has been used this session. */
    var active: Boolean = false
        private set

    fun playWindow(song: Song, next: Song?, startPositionMs: Long, autoPlay: Boolean) {
        active = true
        host.playWindow(song, next, startPositionMs, autoPlay)
        Log.i(TAG, "window via ${host.engineId} '${song.displayTitle}' next=${next?.displayTitle}")
    }

    fun setNext(song: Song?) {
        if (active) host.setNext(song)
    }

    fun hasPreparedNext(): Boolean = active && host.hasPreparedNext()

    fun playPreparedNext(): Boolean = active && host.playPreparedNext()

    fun warmupNext() {
        if (active) host.warmupNext()
    }

    fun deactivate() {
        if (!active) return
        host.pause()
        host.stop()
        active = false
    }

    fun play() {
        if (active) host.play()
    }

    fun pause() {
        if (active) host.pause()
    }

    fun seekTo(ms: Long) {
        if (active) host.seekTo(ms)
    }

    fun isPlaying(): Boolean = active && host.isPlaying()

    fun getPositionMs(): Long = if (active) host.getPositionMs() else 0L

    fun getDurationMs(): Long = if (active) host.getDurationMs() else 0L

    fun getPlayWhenReady(): Boolean = if (active) host.getPlayWhenReady() else false

    fun isBuffering(): Boolean = active && host.isBuffering()

    fun isLive(): Boolean = active && host.isLive()

    fun sessionToken() = host.sessionBridge.sessionToken()

    fun updateMetadata(song: Song?) {
        host.sessionBridge.updateMetadata(song)
    }

    fun setSessionActive(active: Boolean) {
        host.sessionBridge.setSessionActive(active)
    }

    fun release() {
        host.release()
        active = false
    }

    companion object {
        private const val TAG = "EngineHooks"
    }
}
