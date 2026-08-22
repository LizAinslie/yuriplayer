package capital.yuri.yuriplayer.player

import android.content.Context
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.AudioPipeline
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
    private val settings: LibrarySettings,
    sessionActivity: android.app.PendingIntent,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    ended: () -> Unit,
    autoAdvanced: () -> Unit,
    playingChanged: (Boolean) -> Unit,
    onEngineError: (String, Boolean) -> Unit = { _, _ -> }
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
        override fun onEnded() = ended()

        override fun onAutoAdvanced() = autoAdvanced()

        override fun onIsPlayingChanged(playing: Boolean) = playingChanged(playing)

        override fun onError(message: String, recoverable: Boolean) {
            log.e { "engine($engineId) error: $message recoverable=$recoverable" }
            onEngineError(message, recoverable)
        }
    }

    init {
        engine.addListener(listener)
        log.i { "active engine=$engineId" }
    }

    /**
     * Play this audio source. [next] is an optional successor buffer — ignored
     * for live streams. Queue / radio / podcast identity stays in the host.
     */
    fun playWindow(song: Song, next: Song?, startPositionMs: Long, autoPlay: Boolean) {
        val quality = settings.getStreamQuality()
        val current = song.toPlaybackMedia(quality = quality)
        val successor = if (current.live) null else next?.toPlaybackMedia(quality = quality)
        AudioPipeline.notePlay(
            title = song.displayTitle,
            engine = engineId.id,
            quality = quality.id,
            uri = current.uri.toString()
        )
        log.i { "load engine=$engineId '${song.displayTitle}' " +
                "uri=${current.uri} scheme=${current.uri.scheme} quality=${quality.id} " +
                "mime=${song.mimeType} live=${current.live} autoPlay=$autoPlay pos=$startPositionMs " +
                "successor=${successor?.title}" }
        engine.setPlayWhenReady(autoPlay)
        engine.load(current, successor, startPositionMs)
        if (autoPlay) engine.play()
        sessionBridge.updateMetadata(song)
    }

    fun setNext(song: Song?) {
        if (engine.isLive()) {
            engine.setNext(null)
            return
        }
        engine.setNext(song?.toPlaybackMedia(quality = settings.getStreamQuality()))
    }

    fun hasPreparedNext(): Boolean = engine.hasPreparedNext()

    fun preparedNextId(): String? = engine.preparedNextId()

    fun playPreparedNext(): Boolean = engine.playPreparedNext()

    fun warmupNext() = engine.warmupNext()

    fun pause() = engine.pause()
    fun play() = engine.play()
    fun stop() = engine.stop()
    fun seekTo(ms: Long) = engine.seekTo(engine.getCurrentIndex().coerceAtLeast(0), ms)
    fun isPlaying(): Boolean = engine.isPlaying.value
    fun getPositionMs(): Long = engine.getPositionMs()
    fun getDurationMs(): Long = engine.getDurationMs()
    fun getPlayWhenReady(): Boolean = engine.getPlayWhenReady()
    fun isBuffering(): Boolean = engine.isBuffering()
    fun isLive(): Boolean = engine.isLive()

    fun release() {
        engine.removeListener(listener)
        sessionBridge.release()
        engine.release()
    }

    companion object {
        private val log = yuriLog("SelectedEngine")
    }
}
