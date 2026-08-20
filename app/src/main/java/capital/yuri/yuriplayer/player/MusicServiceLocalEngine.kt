package capital.yuri.yuriplayer.player

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.EngineSessionBridge
import capital.yuri.yuriplayer.player.engine.PlaybackEngine
import capital.yuri.yuriplayer.player.engine.PlaybackEngineFactory
import capital.yuri.yuriplayer.player.engine.PlaybackEngineId
import capital.yuri.yuriplayer.player.engine.toPlaybackMedia

/**
 * Owns the **user-selected** [PlaybackEngine] + platform [EngineSessionBridge].
 *
 * One engine plays everything (local + remote). Switching engines in Settings
 * takes effect the next time this host is created (service restart / next
 * play session).
 */
class MusicServiceLocalEngine(
    context: Context,
    settings: LibrarySettings,
    sessionActivity: android.app.PendingIntent,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit
) {
    val engineId: PlaybackEngineId = settings.getPlaybackEngineId()
    val engine: PlaybackEngine = PlaybackEngineFactory.create(context, engineId)

    val sessionBridge = EngineSessionBridge(
        context = context,
        engine = engine,
        sessionActivity = sessionActivity,
        playAction = onPlay,
        pauseAction = onPause,
        nextAction = onNext,
        prevAction = onPrev,
        seekAction = onSeek
    )

    private val listener = object : PlaybackEngine.Listener {
        override fun onEnded() {
            onEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            onPlayingChanged(playing)
        }

        override fun onError(message: String, recoverable: Boolean) {
            Log.e(TAG, "engine($engineId) error: $message recoverable=$recoverable")
        }
    }

    init {
        engine.addListener(listener)
        Log.i(TAG, "active engine=$engineId")
    }

    /** Load current (+ optional next) and optionally start. */
    fun playWindow(song: Song, next: Song?, startPositionMs: Long, autoPlay: Boolean) {
        val items = buildList {
            add(song.toPlaybackMedia())
            if (next != null) add(next.toPlaybackMedia())
        }
        Log.i(
            TAG,
            "playWindow engine=$engineId '${song.displayTitle}' " +
                "uri=${items.firstOrNull()?.uri} scheme=${items.firstOrNull()?.uri?.scheme} " +
                "autoPlay=$autoPlay pos=$startPositionMs"
        )
        engine.setPlayWhenReady(autoPlay)
        engine.setWindow(items, 0, startPositionMs)
        if (autoPlay) engine.play()
        sessionBridge.updateMetadata(song)
    }

    fun pause() = engine.pause()
    fun play() = engine.play()
    fun stop() = engine.stop()
    fun seekTo(ms: Long) = engine.seekTo(0, ms)
    fun isPlaying(): Boolean = engine.isPlaying.value
    fun getPositionMs(): Long = engine.getPositionMs()
    fun getDurationMs(): Long = engine.getDurationMs()
    fun getPlayWhenReady(): Boolean = engine.getPlayWhenReady()

    fun release() {
        engine.removeListener(listener)
        sessionBridge.release()
        engine.release()
    }

    companion object {
        private const val TAG = "SelectedEngine"
    }
}
