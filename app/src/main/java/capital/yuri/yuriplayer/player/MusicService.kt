package capital.yuri.yuriplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
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

class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private val queueManager: QueueManager by inject()
    private val stateStore: PlaybackStateStore by inject()
    private val historyStore: PlaybackHistoryStore by inject()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var restoredOnce = false
    private var advancing = false
    private var recoveringAudio = false
    private var lastHistoryKey: String? = null

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val queueSnapshot: StateFlow<QueueSnapshot> get() = queueManager.snapshot
    val historyEntries: StateFlow<List<HistoryEntry>> get() = historyStore.entries

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
            .setBufferDurationsMs(20_000, 60_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setPauseAtEndOfMediaItems(false)
            .build()
            .also {
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.addListener(playerListener)
            }

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

    private fun songUri(song: Song): Uri = song.contentUri

    private fun mediaItemUriAt(index: Int): Uri? {
        val p = player ?: return null
        if (index < 0 || index >= p.mediaItemCount) return null
        return p.getMediaItemAt(index).localConfiguration?.uri
    }

    private fun rebufferWindow(
        startPositionMs: Long = 0L,
        autoPlay: Boolean = false,
        forceReload: Boolean = false
    ) {
        val p = player ?: return
        val current = queueManager.currentSong()
        if (current == null) {
            p.stop()
            p.clearMediaItems()
            _nowPlaying.value = null
            updateForegroundNotification()
            return
        }

        val next = queueManager.peekNext()
        val wantUris = buildList {
            add(songUri(current))
            if (next != null) add(songUri(next))
        }
        val haveUris = (0 until p.mediaItemCount).mapNotNull { mediaItemUriAt(it) }

        val alreadySynced =
            !forceReload &&
                haveUris == wantUris &&
                p.currentMediaItemIndex == 0

        if (alreadySynced) {
            if (startPositionMs > 0L && kotlin.math.abs(p.currentPosition - startPositionMs) > 400L) {
                p.seekTo(startPositionMs)
            }
            if (autoPlay && !p.isPlaying) p.play()
            _nowPlaying.value = current
            maybeRecordHistory(current)
            updateForegroundNotification()
            return
        }

        Log.i(
            TAG,
            "rebufferWindow current='${current.displayTitle}' next='${next?.displayTitle}' " +
                "startMs=$startPositionMs autoPlay=$autoPlay force=$forceReload"
        )

        val items = buildList {
            add(toMediaItem(current))
            if (next != null) add(toMediaItem(next))
        }

        try {
            val wasPlaying = autoPlay || p.playWhenReady
            p.setMediaItems(items, /* startIndex */ 0, startPositionMs.coerceAtLeast(0L))
            p.prepare()
            p.playWhenReady = wasPlaying
            if (wasPlaying) p.play()
        } catch (e: Exception) {
            Log.e(TAG, "rebufferWindow failed", e)
        }

        _nowPlaying.value = current
        maybeRecordHistory(current)
        updateForegroundNotification()
        persistState()
    }

    private fun hardLoad(song: Song?, startPositionMs: Long = 0L, autoPlay: Boolean = false) {
        if (song == null) {
            player?.stop()
            player?.clearMediaItems()
            _nowPlaying.value = null
            updateForegroundNotification()
            return
        }
        rebufferWindow(startPositionMs, autoPlay, forceReload = true)
    }

    private fun maybeRecordHistory(song: Song) {
        val key = song.path ?: song.contentUri.toString()
        if (key == lastHistoryKey) return
        lastHistoryKey = key
        historyStore.record(song)
    }

    private fun applyAdvance(result: QueueManager.AdvanceResult, autoPlay: Boolean = true) {
        val p = player
        when {
            result.finished -> {
                p?.pause()
                _nowPlaying.value = queueManager.currentSong()
                updateForegroundNotification()
                persistState()
            }
            result.seekToStart -> {
                p?.seekTo(0)
                if (autoPlay) p?.play()
                // Keep now-playing pointing at the same track
                _nowPlaying.value = queueManager.currentSong() ?: result.song
                updateForegroundNotification()
                persistState()
            }
            result.reload -> {
                Log.i(TAG, "repeat-one seekTo(0)")
                p?.seekTo(0)
                if (autoPlay) p?.play()
                _nowPlaying.value = queueManager.currentSong() ?: result.song
                updateForegroundNotification()
                persistState()
            }
            result.song != null -> {
                val target = result.song
                // Publish UI-facing now-playing *before* touching the player so
                // the next poll / collector sees the new track immediately.
                _nowPlaying.value = target
                maybeRecordHistory(target)
                updateForegroundNotification()

                val nextItemUri = mediaItemUriAt(1)
                val canSeamless =
                    p != null &&
                        p.hasNextMediaItem() &&
                        nextItemUri != null &&
                        nextItemUri == songUri(target)

                if (canSeamless) {
                    Log.i(TAG, "seamless advance → '${target.displayTitle}'")
                    p.seekToNextMediaItem()
                    if (autoPlay) p.play()
                    persistState()
                    serviceScope.launch {
                        delay(150)
                        val pl = player ?: return@launch
                        val pos = pl.currentPosition.coerceAtLeast(0L)
                        rebufferWindow(
                            startPositionMs = pos,
                            autoPlay = pl.playWhenReady,
                            forceReload = true
                        )
                    }
                } else {
                    Log.i(TAG, "fallback rebuffer advance → '${target.displayTitle}'")
                    rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
                }
            }
        }
    }

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        source: ColdSource? = null
    ) {
        queueManager.playSource(songs, startIndex, source)
        rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String) {
        if (queueManager.updateColdFromSource(songs, sourceId)) {
            rebufferWindow(
                startPositionMs = player?.currentPosition ?: 0L,
                autoPlay = player?.playWhenReady == true,
                forceReload = false
            )
            persistState()
        }
    }

    fun addToHotQueue(song: Song) {
        queueManager.addToQueue(song)
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun addToHotQueue(songs: List<Song>) {
        queueManager.addToQueue(songs)
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun clearHotQueue() {
        queueManager.clearHotQueue()
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun removeFromHot(index: Int) {
        val needReload = queueManager.removeFromQueue(index)
        rebufferWindow(
            startPositionMs = if (needReload) 0L else player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = needReload
        )
        persistState()
    }

    fun removeFromCold(index: Int) {
        val needReload = queueManager.removeFromContext(index)
        rebufferWindow(
            startPositionMs = if (needReload) 0L else player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = needReload
        )
        persistState()
    }

    fun moveHot(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun moveCold(from: Int, to: Int) {
        queueManager.moveInContext(from, to)
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun moveColdToHot(index: Int) {
        val needReload = queueManager.moveColdToHot(index)
        rebufferWindow(
            startPositionMs = if (needReload) 0L else player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = needReload
        )
        persistState()
    }

    fun playQueueItem(lane: QueueLane, index: Int) {
        queueManager.playItem(lane, index)
        rebufferWindow(0L, autoPlay = true, forceReload = true)
    }

    fun setShuffle(enabled: Boolean) {
        queueManager.setShuffle(enabled)
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        player?.repeatMode = Player.REPEAT_MODE_OFF
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        player?.repeatMode = Player.REPEAT_MODE_OFF
        rebufferWindow(
            startPositionMs = player?.currentPosition ?: 0L,
            autoPlay = player?.playWhenReady == true,
            forceReload = false
        )
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

    fun peekNext(): Song? = queueManager.peekNext()
    fun peekPrevious(): Song? = queueManager.peekPrevious()

    fun clearHistory() = historyStore.clear()
    fun getHistory(): List<HistoryEntry> = historyStore.entries.value
    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
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
            rebufferWindow(saved.positionMs, autoPlay = false, forceReload = true)
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

    private fun recoverFromAudioGlitch() {
        if (recoveringAudio) return
        recoveringAudio = true
        serviceScope.launch {
            try {
                val wasPlaying = player?.playWhenReady == true || player?.isPlaying == true
                val pos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                Log.w(TAG, "audio glitch recovery pos=$pos")
                rebufferWindow(pos, autoPlay = wasPlaying, forceReload = true)
            } finally {
                delay(300)
                recoveringAudio = false
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            updateForegroundNotification()
            persistState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Prefer queue manager truth; fall back to matching media id
            val song = queueManager.currentSong() ?: _nowPlaying.value
            if (song != null) {
                _nowPlaying.value = song
                maybeRecordHistory(song)
                updateForegroundNotification()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !advancing) {
                advancing = true
                try {
                    applyAdvance(queueManager.advance(userInitiated = false))
                } finally {
                    advancing = false
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error code=${error.errorCode} ${error.message}", error)
            val cause = error.cause
            if (cause is AudioSink.UnexpectedDiscontinuityException ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            ) {
                recoverFromAudioGlitch()
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

    /** Prefer the UI-facing now-playing slot so advances show up immediately. */
    fun getCurrentSong(): Song? = _nowPlaying.value ?: queueManager.currentSong()

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
        Log.i(TAG, "onTaskRemoved — stopping playback")
        persistState()
        try {
            player?.playWhenReady = false
            player?.pause()
            player?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop on task removed", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
