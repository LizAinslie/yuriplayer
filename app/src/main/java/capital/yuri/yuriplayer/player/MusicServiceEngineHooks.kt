package capital.yuri.yuriplayer.player

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.isNetworkUri
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
import capital.yuri.yuriplayer.player.engine.resolvePlayableUri

/**
 * Bridges [MusicService] to [MusicServiceLocalEngine] for on-device files.
 * Network / virtual library paths stay on ExoPlayer.
 */
internal class MusicServiceEngineHooks(
    context: Context,
    sessionActivity: PendingIntent,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onEnded: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit
) {
    private val local = MusicServiceLocalEngine(
        context = context,
        sessionActivity = sessionActivity,
        onPlay = onPlay,
        onPause = onPause,
        onNext = onNext,
        onPrev = onPrev,
        onSeek = onSeek,
        onEnded = onEnded,
        onPlayingChanged = onPlayingChanged
    )

    /** True while the active track is playing through LibVLC. */
    var localActive: Boolean = false
        private set

    fun shouldUseLocal(song: Song?): Boolean {
        if (song == null) return false
        if (isVirtualLibraryPath(song.path)) return false
        val uri = resolvePlayableUri(song)
        if (isNetworkUri(uri)) return false
        return true
    }

    fun playLocal(song: Song, next: Song?, startPositionMs: Long, autoPlay: Boolean) {
        localActive = true
        local.playLocal(song, next, startPositionMs, autoPlay)
        Log.i(TAG, "local engine active for '${song.displayTitle}'")
    }

    fun deactivateLocal() {
        if (!localActive) return
        local.pause()
        local.stop()
        localActive = false
    }

    fun play() {
        if (localActive) local.play()
    }

    fun pause() {
        if (localActive) local.pause()
    }

    fun seekTo(ms: Long) {
        if (localActive) local.seekTo(ms)
    }

    fun isPlaying(): Boolean = localActive && local.isPlaying()

    fun getPositionMs(): Long = if (localActive) local.getPositionMs() else 0L

    fun getDurationMs(): Long = if (localActive) local.getDurationMs() else 0L

    fun getPlayWhenReady(): Boolean = if (localActive) local.getPlayWhenReady() else false

    fun sessionToken() = local.sessionBridge.sessionToken()

    fun updateMetadata(song: Song?) {
        if (localActive) local.sessionBridge.updateMetadata(song)
    }

    fun release() {
        local.release()
        localActive = false
    }

    companion object {
        private const val TAG = "EngineHooks"
    }
}
