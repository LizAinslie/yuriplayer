package capital.yuri.yuriplayer.player.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import capital.yuri.yuriplayer.data.Song

/**
 * Platform [MediaSession] driven purely by a [PlaybackEngine].
 *
 * Independent of ExoPlayer / Media3 session so LibVLC (and future backends)
 * get lock-screen + headset controls without implementing Media3's Player.
 */
class EngineSessionBridge(
    context: Context,
    private val engine: PlaybackEngine,
    sessionActivity: PendingIntent,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onSeek: (Long) -> Unit
) {
    private val appContext = context.applicationContext

    val session: MediaSession = MediaSession(appContext, "YuriPlayer").apply {
        setSessionActivity(sessionActivity)
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = onPlay()
            override fun onPause() = onPause()
            override fun onSkipToNext() = onNext()
            override fun onSkipToPrevious() = onPrev()
            override fun onSeekTo(pos: Long) = onSeek(pos)
            override fun onStop() = onPause()
        })
        isActive = true
    }

    private val listener = object : PlaybackEngine.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            publishState()
        }

        override fun onPlaybackStateChanged(state: PlaybackEngine.PlaybackState) {
            publishState()
        }
    }

    init {
        engine.addListener(listener)
        publishState()
    }

    fun updateMetadata(song: Song?) {
        if (song == null) {
            session.setMetadata(null)
            return
        }
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.displayTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.displayArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, song.displayAlbum)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, song.displayAlbumArtist)
            .putLong(
                MediaMetadata.METADATA_KEY_DURATION,
                song.durationMs.takeIf { it > 0 } ?: engine.getDurationMs()
            )
        song.albumArtUri?.let {
            b.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it.toString())
            b.putString(MediaMetadata.METADATA_KEY_ART_URI, it.toString())
        }
        session.setMetadata(b.build())
        publishState()
    }

    fun publishState() {
        val playing = engine.isPlaying.value || engine.getPlayWhenReady()
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            engine.getMediaCount() <= 0 -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val pb = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                state,
                engine.getPositionMs(),
                if (playing) 1f else 0f
            )
        session.setPlaybackState(pb.build())
    }

    fun release() {
        engine.removeListener(listener)
        session.isActive = false
        session.release()
    }

    fun sessionToken(): MediaSession.Token = session.sessionToken
}
