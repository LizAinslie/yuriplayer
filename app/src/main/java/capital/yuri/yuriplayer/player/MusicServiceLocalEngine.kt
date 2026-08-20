package capital.yuri.yuriplayer.player

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.EngineSessionBridge
import capital.yuri.yuriplayer.player.engine.HybridPlaybackEngine
import capital.yuri.yuriplayer.player.engine.PlaybackEngine
import capital.yuri.yuriplayer.player.engine.toPlaybackMedia

/**
 * Owns the hybrid (VLC local + Media3 stream) engine + platform MediaSession
 * for local playback so FLAC/APE work even when ExoPlayer rejects the file.
 *
 * [MusicService] still drives queue policy; this is only the audio backend for
 * on-device files.
 */
class MusicServiceLocalEngine(
    context: Context,
    sessionActivity: android.app.PendingIntent,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit
) {
    val engine: HybridPlaybackEngine = HybridPlaybackEngine(context)

    val sessionBridge = EngineSessionBridge(
        context = context,
        engine = engine,
        sessionActivity = sessionActivity,
        onPlay = onPlay,
        onPause = onPause,
        onNext = onNext,
        onPrev = onPrev,
        onSeek = onSeek
    )

    private val listener = object : PlaybackEngine.Listener {
        override fun onEnded() {
            onEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            onPlayingChanged(playing)
        }

        override fun onError(message: String, recoverable: Boolean) {
            Log.e(TAG, "engine error: $message recoverable=$recoverable")
        }
    }

    init {
        engine.addListener(listener)
    }

    fun playLocal(song: Song, next: Song?, startPositionMs: Long, autoPlay: Boolean) {
        val items = buildList {
            add(song.toPlaybackMedia())
            if (next != null) add(next.toPlaybackMedia())
        }
        engine.setPlayWhenReady(autoPlay)
        engine.setWindow(items, 0, startPositionMs)
        if (autoPlay) engine.play()
        sessionBridge.updateMetadata(song)
        Log.i(TAG, "playLocal '${song.displayTitle}' autoPlay=$autoPlay pos=$startPositionMs")
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
        private const val TAG = "LocalEngine"
    }
}
