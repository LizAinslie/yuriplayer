package capital.yuri.yuriplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.Song

class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var currentPlaylist: List<Song> = emptyList()

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this).build().also {
            it.addListener(playerListener)
        }
        player = exo

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()
    }

    private val playerListener = object : Player.Listener {
        // Future: emit state changes to a shared flow if needed
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Allow both MediaSessionService binding and our LocalBinder
        val superBinder = super.onBind(intent)
        return if (intent?.action == null) binder else superBinder ?: binder
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        currentPlaylist = songs
        val safeIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))

        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.contentUri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri)
                        .build()
                )
                .build()
        }

        player?.apply {
            setMediaItems(mediaItems, safeIndex, 0L)
            prepare()
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipToNext() {
        player?.seekToNextMediaItem()
        player?.play()
    }

    fun skipToPrevious() {
        player?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
            it.play()
        }
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun getCurrentSong(): Song? {
        val index = player?.currentMediaItemIndex ?: return null
        return currentPlaylist.getOrNull(index)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
