package capital.yuri.yuriplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import androidx.media3.session.MediaStyleNotificationHelper
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * ExoPlayer + notification host. Queue logic lives in [QueueManager].
 *
 * Repeat-one uses ExoPlayer's native [Player.REPEAT_MODE_ONE] so the audio sink
 * loops cleanly (avoids UnexpectedDiscontinuityException from seek-after-ENDED).
 */
class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private val queueManager: QueueManager by inject()
    private val stateStore: PlaybackStateStore by inject()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var restoredOnce = false
    private var advancing = false

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val queueSnapshot: StateFlow<QueueSnapshot> get() = queueManager.snapshot

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildMediaNotification(null, false))

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(playerListener) }

        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(openPlayerPendingIntent())
            .build()

        restorePlaybackState()
        startPeriodicPersist()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> skipToNext()
            ACTION_PREV -> skipToPrevious()
            else -> startForeground(
                NOTIFICATION_ID,
                buildMediaNotification(_nowPlaying.value, _isPlaying.value)
            )
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Keep ExoPlayer loop mode in sync so repeat-one is seamless. */
    private fun syncPlayerRepeatMode() {
        val mode = queueManager.getSnapshot().repeatMode
        val exo = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.OFF, RepeatMode.COLD -> Player.REPEAT_MODE_OFF
        }
        if (player?.repeatMode != exo) {
            Log.i(TAG, "syncPlayerRepeatMode app=$mode exo=$exo")
            player?.repeatMode = exo
        }
    }

    private fun loadSong(song: Song?, startPositionMs: Long = 0L, autoPlay: Boolean = false) {
        if (song == null) {
            Log.i(TAG, "loadSong: null")
            player?.stop()
            _nowPlaying.value = null
            updateForegroundNotification()
            return
        }
        Log.i(
            TAG,
            "loadSong title='${song.displayTitle}' path=${song.path} uri=${song.contentUri} " +
                "startMs=$startPositionMs autoPlay=$autoPlay"
        )
        Log.d(TAG, "loadSong id=${song.id} mime=${song.mimeType} duration=${song.durationMs}")

        // Always set a fresh media item so the audio sink resets cleanly
        player?.apply {
            setMediaItem(toMediaItem(song), /* startPositionMs= */ startPositionMs)
            prepare()
            playWhenReady = autoPlay
            if (autoPlay) play()
        }
        syncPlayerRepeatMode()
        _nowPlaying.value = song
        updateForegroundNotification()
        persistState()
    }

    private fun applyAdvance(result: QueueManager.AdvanceResult, autoPlay: Boolean = true) {
        when {
            result.seekToStart -> {
                player?.seekTo(0)
                if (autoPlay) player?.play()
                persistState()
            }
            result.reload -> {
                // Prefer native loop; if we still got here, hard-reset the track
                val song = result.song ?: queueManager.currentSong()
                Log.i(TAG, "applyAdvance reload path=${song?.path}")
                player?.stop()
                loadSong(song, 0L, autoPlay)
            }
            result.song != null -> loadSong(result.song, 0L, autoPlay)
            result.finished -> {
                player?.pause()
                _nowPlaying.value = queueManager.currentSong()
                updateForegroundNotification()
                persistState()
            }
        }
    }

    fun playSource(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        queueManager.playSource(songs, startIndex)
        loadSong(queueManager.currentSong(), 0L, autoPlay)
    }

    fun addToHotQueue(song: Song) {
        queueManager.addToQueue(song)
        persistState()
    }

    fun addToHotQueue(songs: List<Song>) {
        queueManager.addToQueue(songs)
        persistState()
    }

    fun removeFromHot(index: Int) {
        val needReload = queueManager.removeFromQueue(index)
        if (needReload) loadSong(queueManager.currentSong(), 0L, player?.playWhenReady == true)
        else persistState()
    }

    fun removeFromCold(index: Int) {
        val needReload = queueManager.removeFromContext(index)
        if (needReload) loadSong(queueManager.currentSong(), 0L, player?.playWhenReady == true)
        else persistState()
    }

    fun moveHot(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        persistState()
    }

    fun moveCold(from: Int, to: Int) {
        queueManager.moveInContext(from, to)
        persistState()
    }

    fun playQueueItem(lane: QueueLane, index: Int) {
        queueManager.playItem(lane, index)
        loadSong(queueManager.currentSong(), 0L, autoPlay = true)
    }

    fun setShuffle(enabled: Boolean) {
        queueManager.setShuffle(enabled)
        persistState()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncPlayerRepeatMode()
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        syncPlayerRepeatMode()
        persistState()
    }

    fun play() {
        player?.play()
        updateForegroundNotification()
        persistState()
    }

    fun pause() {
        player?.pause()
        updateForegroundNotification()
        persistState()
    }

    fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        updateForegroundNotification()
        persistState()
    }

    fun skipToNext() {
        if (advancing) return
        advancing = true
        try {
            // User skip always advances even in repeat-one
            applyAdvance(queueManager.advance(userInitiated = true))
        } finally {
            advancing = false
        }
    }

    fun skipToPrevious() {
        applyAdvance(queueManager.skipPrevious(player?.currentPosition ?: 0L))
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        persistState()
    }

    private fun toMediaItem(song: Song): MediaItem =
        MediaItem.Builder()
            .setUri(song.contentUri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.displayTitle)
                    .setArtist(song.displayArtist)
                    .setAlbumTitle(song.displayAlbum)
                    .setAlbumArtist(song.displayAlbumArtist)
                    .setArtworkUri(song.albumArtUri)
                    .build()
            )
            .build()

    private fun restorePlaybackState() {
        if (restoredOnce) return
        restoredOnce = true
        serviceScope.launch {
            val saved = withContext(Dispatchers.IO) { stateStore.load() } ?: return@launch
            queueManager.restore(saved.snapshot)
            loadSong(queueManager.currentSong(), saved.positionMs, autoPlay = false)
            syncPlayerRepeatMode()
        }
    }

    private fun startPeriodicPersist() {
        persistJob?.cancel()
        persistJob = serviceScope.launch {
            while (isActive) {
                delay(5_000)
                persistState()
            }
        }
    }

    private fun persistState() {
        stateStore.save(
            snapshot = queueManager.getSnapshot(),
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            playWhenReady = player?.playWhenReady == true
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateForegroundNotification()
            persistState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?$playbackState"
            }
            Log.d(TAG, "onPlaybackStateChanged $name exoRepeat=${player?.repeatMode}")

            // With REPEAT_MODE_ONE, ExoPlayer loops internally and should not end.
            // Only advance our dual-queue when the player is not in native one-loop.
            if (playbackState == Player.STATE_ENDED && !advancing) {
                if (player?.repeatMode == Player.REPEAT_MODE_ONE) {
                    Log.i(TAG, "STATE_ENDED under REPEAT_MODE_ONE — restarting track cleanly")
                    val song = queueManager.currentSong()
                    player?.stop()
                    loadSong(song, 0L, autoPlay = true)
                    return
                }
                advancing = true
                try {
                    applyAdvance(queueManager.advance(userInitiated = false))
                } finally {
                    advancing = false
                }
            }
        }
    }

    private fun updateForegroundNotification() {
        startForeground(
            NOTIFICATION_ID,
            buildMediaNotification(_nowPlaying.value, _isPlaying.value)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun openPlayerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(
            this, REQUEST_OPEN_PLAYER, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionPending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaNotification(song: Song?, playing: Boolean): Notification {
        val title = song?.displayTitle ?: "Yuri Player"
        val text = song?.displayArtist ?: if (playing) "Playing" else "Paused"
        val playPauseIcon =
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPlayerPendingIntent())
            .setDeleteIntent(serviceActionPending(ACTION_PAUSE, REQUEST_DELETE))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                serviceActionPending(ACTION_PREV, REQUEST_PREV)
            )
            .addAction(
                playPauseIcon, if (playing) "Pause" else "Play",
                serviceActionPending(ACTION_TOGGLE, REQUEST_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                serviceActionPending(ACTION_NEXT, REQUEST_NEXT)
            )

        mediaSession?.let {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(it)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == null) binder else super.onBind(intent) ?: binder
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun getCurrentSong(): Song? = queueManager.currentSong()
    fun getPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun getQueueSnapshot(): QueueSnapshot = queueManager.getSnapshot()
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) =
        playSource(songs, startIndex, autoPlay = false)
    fun getQueue(): List<Song> = queueManager.getSnapshot().flatQueue
    fun getCurrentIndex(): Int {
        val snap = queueManager.getSnapshot()
        return when (snap.lane) {
            QueueLane.HOT -> snap.indexInLane
            QueueLane.COLD -> snap.hotQueue.size + snap.indexInLane
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistState()
        if (player?.isPlaying == true || player?.playWhenReady == true) return
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistState()
        persistJob?.cancel()
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
        private const val TAG = "YuriPlayer"
        const val CHANNEL_ID = "yuri_playback"
        const val NOTIFICATION_ID = 42
        const val ACTION_PLAY = "capital.yuri.yuriplayer.action.PLAY"
        const val ACTION_PAUSE = "capital.yuri.yuriplayer.action.PAUSE"
        const val ACTION_TOGGLE = "capital.yuri.yuriplayer.action.TOGGLE"
        const val ACTION_NEXT = "capital.yuri.yuriplayer.action.NEXT"
        const val ACTION_PREV = "capital.yuri.yuriplayer.action.PREV"
        private const val REQUEST_OPEN_PLAYER = 100
        private const val REQUEST_PREV = 101
        private const val REQUEST_TOGGLE = 102
        private const val REQUEST_NEXT = 103
        private const val REQUEST_DELETE = 104
    }
}
