package capital.yuri.yuriplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var currentPlaylist: List<Song> = emptyList()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Call startForeground immediately so Oreo does not kill us within 5s of
        // startForegroundService(). Media3 will replace this with a richer media notification.
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification("Yuri Player", "Starting…"))

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(playerListener) }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure we are always in the foreground once started from the UI
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification(
            _nowPlaying.value?.title ?: "Yuri Player",
            _nowPlaying.value?.artist ?: "Playing in background"
        ))
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateForegroundNotification()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player?.currentMediaItemIndex ?: -1
            _nowPlaying.value = currentPlaylist.getOrNull(index)
            updateForegroundNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                val index = player?.currentMediaItemIndex ?: -1
                _nowPlaying.value = currentPlaylist.getOrNull(index)
                updateForegroundNotification()
            }
        }
    }

    private fun updateForegroundNotification() {
        val song = _nowPlaying.value
        val title = song?.title ?: "Yuri Player"
        val text = song?.artist ?: if (_isPlaying.value) "Playing" else "Paused"
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification(title, text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildPlaceholderNotification(title: String, text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == null) {
            binder
        } else {
            super.onBind(intent) ?: binder
        }
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        serviceScope.launch {
            val safeIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
            currentPlaylist = songs
            _queue.value = songs

            val mediaItems = withContext(Dispatchers.Default) {
                songs.map { song ->
                    MediaItem.Builder()
                        .setUri(song.contentUri)
                        .setMediaId(song.id.toString())
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setAlbumTitle(song.album)
                                .setAlbumArtist(song.effectiveAlbumArtist)
                                .setArtworkUri(song.albumArtUri)
                                .build()
                        )
                        .build()
                }
            }

            player?.apply {
                setMediaItems(mediaItems, safeIndex, 0L)
                prepare()
            }

            _nowPlaying.value = songs.getOrNull(safeIndex)
            updateForegroundNotification()
        }
    }

    fun play() {
        player?.play()
        updateForegroundNotification()
    }

    fun pause() {
        player?.pause()
        updateForegroundNotification()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
        updateForegroundNotification()
    }

    fun skipToNext() {
        player?.seekToNextMediaItem()
        player?.play()
    }

    fun skipToPrevious() {
        player?.let {
            if (it.currentPosition > 3000L) {
                it.seekTo(0)
            } else if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
            it.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun getCurrentSong(): Song? {
        val index = player?.currentMediaItemIndex ?: return null
        return currentPlaylist.getOrNull(index)
    }

    fun getCurrentIndex(): Int = player?.currentMediaItemIndex ?: -1

    fun getPositionMs(): Long {
        val pos = player?.currentPosition ?: 0L
        return if (pos < 0) 0L else pos
    }

    fun getDurationMs(): Long {
        val dur = player?.duration ?: 0L
        return if (dur < 0) 0L else dur
    }

    fun getQueue(): List<Song> = currentPlaylist

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing after swipe-away if audio is active
        if (player?.isPlaying == true || player?.playWhenReady == true) {
            return
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player?.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "yuri_playback"
        const val NOTIFICATION_ID = 42
    }
}
