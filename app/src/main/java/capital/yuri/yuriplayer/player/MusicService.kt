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
import java.io.File

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
    private var loopGeneration = 0L

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
                // Never use Exo's own REPEAT_MODE_ONE — on API 27 same-URI
                // seeks/transitions kill the AudioTrack (UnexpectedDiscontinuity).
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

    private fun isRepeatOne(): Boolean =
        queueManager.getSnapshot().repeatMode == RepeatMode.ONE

    private fun songUri(song: Song): Uri {
        val path = song.path
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.canRead()) return Uri.fromFile(file)
        }
        return song.contentUri
    }

    private fun mediaItemUriAt(index: Int): Uri? {
        val p = player ?: return null
        if (index < 0 || index >= p.mediaItemCount) return null
        return p.getMediaItemAt(index).localConfiguration?.uri
    }

    private fun urisEqual(a: Uri?, b: Uri?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        return a.lastPathSegment != null && a.lastPathSegment == b.lastPathSegment
    }

    private fun nextLoopItem(song: Song): MediaItem {
        loopGeneration++
        return toMediaItem(song, mediaIdSuffix = "loop-$loopGeneration")
    }

    /**
     * Full stop → clear → prepare of a *single* media item.
     * Required on API 27: transitioning between two items that share the same
     * URI leaves DefaultAudioSink in a dead state (UnexpectedDiscontinuity +
     * silence while the clock keeps running).
     */
    private fun hardRestartCurrent(autoPlay: Boolean = true) {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        val wasPlaying = autoPlay || p.playWhenReady
        Log.i(TAG, "hardRestartCurrent '${current.displayTitle}' gen=$loopGeneration")
        try {
            p.playWhenReady = false
            p.stop()
            p.clearMediaItems()
            p.setMediaItem(nextLoopItem(current), /* resetPosition = */ true)
            p.prepare()
            p.repeatMode = Player.REPEAT_MODE_OFF
            if (wasPlaying) {
                p.playWhenReady = true
                p.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "hardRestartCurrent failed", e)
            rebufferWindow(0L, autoPlay = wasPlaying, forceReload = true)
            return
        }
        _nowPlaying.value = current
        updateForegroundNotification()
        persistState()
    }

    /**
     * After seamless advance between *different* tracks: drop finished items and
     * attach the true next. Never used for repeat-one (same URI).
     */
    private fun softNormalizeWindow() {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        if (isRepeatOne()) return
        try {
            while (p.currentMediaItemIndex > 0) p.removeMediaItem(0)
            while (p.mediaItemCount > 1) p.removeMediaItem(p.mediaItemCount - 1)
            queueManager.peekNext()?.let { p.addMediaItem(toMediaItem(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "softNormalizeWindow failed", e)
        }
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

        val repeatOne = isRepeatOne()
        // Repeat-one: ONE item only. A second same-URI item is what triggers
        // UnexpectedDiscontinuityException on Android 8.1 audio track.
        val nextSong = if (repeatOne) null else queueManager.peekNext()
        val wantCount = if (repeatOne) 1 else 1 + (if (nextSong != null) 1 else 0)
        val haveUris = (0 until p.mediaItemCount).mapNotNull { mediaItemUriAt(it) }
        val currentUri = songUri(current)

        val alreadySynced = !forceReload &&
            p.currentMediaItemIndex == 0 &&
            haveUris.size == wantCount &&
            haveUris.isNotEmpty() &&
            urisEqual(haveUris[0], currentUri) &&
            (if (nextSong != null) {
                haveUris.size >= 2 && urisEqual(haveUris[1], songUri(nextSong))
            } else true)

        if (alreadySynced) {
            if (startPositionMs > 0L && kotlin.math.abs(p.currentPosition - startPositionMs) > 400L) {
                p.seekTo(startPositionMs)
            }
            if (autoPlay && !p.isPlaying) p.play()
            p.repeatMode = Player.REPEAT_MODE_OFF
            _nowPlaying.value = current
            maybeRecordHistory(current)
            updateForegroundNotification()
            return
        }

        val items = if (repeatOne) {
            listOf(nextLoopItem(current))
        } else {
            buildList {
                add(toMediaItem(current))
                if (nextSong != null) add(toMediaItem(nextSong))
            }
        }

        Log.i(
            TAG,
            "rebufferWindow current='${current.displayTitle}' " +
                "next='${if (repeatOne) "(repeat-one single)" else nextSong?.displayTitle}' " +
                "startMs=$startPositionMs autoPlay=$autoPlay force=$forceReload"
        )

        try {
            val wasPlaying = autoPlay || p.playWhenReady
            p.setMediaItems(items, 0, startPositionMs.coerceAtLeast(0L))
            p.prepare()
            p.repeatMode = Player.REPEAT_MODE_OFF
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

    /**
     * Soft queue-window sync: never touches the currently playing item.
     * Used for shuffle/repeat toggles and queue mutations so playback stays continuous.
     */
    private fun updateNextMediaItemOnly() {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        val playingUri = mediaItemUriAt(p.currentMediaItemIndex)
        if (!urisEqual(playingUri, songUri(current))) {
            rebufferWindow(
                startPositionMs = p.currentPosition.coerceAtLeast(0L),
                autoPlay = p.playWhenReady,
                forceReload = true
            )
            return
        }
        // Strip anything after the playing item
        while (p.mediaItemCount > p.currentMediaItemIndex + 1) {
            p.removeMediaItem(p.mediaItemCount - 1)
        }
        // Repeat-one stays single-item; never prebuffer a same-URI copy
        if (!isRepeatOne()) {
            queueManager.peekNext()?.let { p.addMediaItem(toMediaItem(it)) }
        }
        p.repeatMode = Player.REPEAT_MODE_OFF
        _nowPlaying.value = current
        updateForegroundNotification()
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
                // Restart current (previous button past 3s). Prefer hard restart
                // over seekTo(0) so the audio sink can't go silent on API 27.
                hardRestartCurrent(autoPlay = autoPlay)
            }
            result.reload -> hardRestartCurrent(autoPlay = autoPlay)
            result.song != null -> {
                val target = result.song
                _nowPlaying.value = target
                maybeRecordHistory(target)
                updateForegroundNotification()

                val nextItemUri = mediaItemUriAt(1)
                val canSeamless =
                    p != null &&
                        !isRepeatOne() &&
                        p.hasNextMediaItem() &&
                        nextItemUri != null &&
                        urisEqual(nextItemUri, songUri(target))

                if (canSeamless) {
                    Log.i(TAG, "seamless advance → '${target.displayTitle}'")
                    p.seekToNextMediaItem()
                    if (autoPlay) p.play()
                    softNormalizeWindow()
                    persistState()
                } else {
                    Log.i(TAG, "fallback rebuffer advance → '${target.displayTitle}'")
                    rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
                }
            }
        }
    }

    private fun syncQueueAfterExoAutoAdvance() {
        if (advancing) return
        // Should not happen under repeat-one (single item), but if it does:
        if (isRepeatOne()) {
            hardRestartCurrent(autoPlay = true)
            return
        }
        advancing = true
        try {
            val result = queueManager.advance(userInitiated = false)
            when {
                result.reload -> hardRestartCurrent(autoPlay = true)
                result.finished -> {
                    player?.pause()
                    _nowPlaying.value = null
                    updateForegroundNotification()
                    persistState()
                }
                result.song != null -> {
                    Log.i(TAG, "auto-transition → queue now '${result.song.displayTitle}'")
                    _nowPlaying.value = result.song
                    maybeRecordHistory(result.song)
                    updateForegroundNotification()
                    softNormalizeWindow()
                    persistState()
                }
            }
        } finally {
            advancing = false
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
            updateNextMediaItemOnly()
            persistState()
        }
    }

    fun addToHotQueue(song: Song) {
        queueManager.addToQueue(song)
        updateNextMediaItemOnly()
        persistState()
    }

    fun addToHotQueue(songs: List<Song>) {
        queueManager.addToQueue(songs)
        updateNextMediaItemOnly()
        persistState()
    }

    fun clearHotQueue() {
        queueManager.clearHotQueue()
        updateNextMediaItemOnly()
        persistState()
    }

    fun removeFromHot(index: Int) {
        val needReload = queueManager.removeFromQueue(index)
        if (needReload) rebufferWindow(0L, autoPlay = player?.playWhenReady == true, forceReload = true)
        else updateNextMediaItemOnly()
        persistState()
    }

    fun removeFromCold(index: Int) {
        val needReload = queueManager.removeFromContext(index)
        if (needReload) rebufferWindow(0L, autoPlay = player?.playWhenReady == true, forceReload = true)
        else updateNextMediaItemOnly()
        persistState()
    }

    fun moveHot(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        updateNextMediaItemOnly()
        persistState()
    }

    fun moveCold(from: Int, to: Int) {
        queueManager.moveInContext(from, to)
        updateNextMediaItemOnly()
        persistState()
    }

    fun moveColdToHot(index: Int) {
        val needReload = queueManager.moveColdToHot(index)
        if (needReload) rebufferWindow(0L, autoPlay = player?.playWhenReady == true, forceReload = true)
        else updateNextMediaItemOnly()
        persistState()
    }

    fun playQueueItem(lane: QueueLane, index: Int) {
        queueManager.playItem(lane, index)
        rebufferWindow(0L, autoPlay = true, forceReload = true)
    }

    fun setShuffle(enabled: Boolean) {
        queueManager.setShuffle(enabled)
        updateNextMediaItemOnly()
        persistState()
    }

    /**
     * Repeat mode only changes whether we prebuffer a *next* item.
     * Never force-reload the playing media item — that is what caused the
     * audible hitch on every repeat toggle (shuffle already used the soft path).
     */
    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        updateNextMediaItemOnly()
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        updateNextMediaItemOnly()
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

    private fun toMediaItem(song: Song, mediaIdSuffix: String? = null): MediaItem {
        val id = if (mediaIdSuffix != null) "${song.id}-$mediaIdSuffix" else song.id.toString()
        return MediaItem.Builder()
            .setUri(songUri(song))
            .setMediaId(id)
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
    }

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
                Log.w(TAG, "audio glitch → hard restart")
                hardRestartCurrent(autoPlay = wasPlaying)
            } finally {
                delay(400)
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
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                hardRestartCurrent(autoPlay = true)
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                syncQueueAfterExoAutoAdvance()
                return
            }
            if (advancing) return
            val song = queueManager.currentSong() ?: _nowPlaying.value
            if (song != null) {
                _nowPlaying.value = song
                maybeRecordHistory(song)
                updateForegroundNotification()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !advancing) {
                if (isRepeatOne()) {
                    Log.i(TAG, "STATE_ENDED under repeat-one → hardRestart")
                    hardRestartCurrent(autoPlay = true)
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
        startForeground(NOTIFICATION_ID, buildMediaNotification(_nowPlaying.value, _isPlaying.value))
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
            .addAction(android.R.drawable.ic_media_previous, "Previous", serviceActionPending(ACTION_PREV, REQUEST_PREV))
            .addAction(playPauseIcon, if (playing) "Pause" else "Play", serviceActionPending(ACTION_TOGGLE, REQUEST_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "Next", serviceActionPending(ACTION_NEXT, REQUEST_NEXT))

        mediaSession?.let {
            builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == null) binder else super.onBind(intent) ?: binder
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun getCurrentSong(): Song? = _nowPlaying.value ?: queueManager.currentSong()
    fun getPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun getQueueSnapshot(): QueueSnapshot = queueManager.getSnapshot()
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) = playSource(songs, startIndex, autoPlay = false)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
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
