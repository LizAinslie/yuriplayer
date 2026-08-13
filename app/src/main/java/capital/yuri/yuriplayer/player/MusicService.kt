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

class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var stateStore: PlaybackStateStore

    /** User-added queue (Spotify "Queue"). Plays next after the current track. */
    private val hotQueue = mutableListOf<Song>()

    /** Context source (album / playlist / list). Resumed after queue drains. */
    private var coldOriginal: List<Song> = emptyList()
    private val coldQueue = mutableListOf<Song>()

    private var lane = QueueLane.COLD
    private var indexInLane = -1

    /** Where to continue in cold after the user queue is drained. */
    private var coldResumeIndex = 0

    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF
    private var advancing = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var restoredOnce = false

    private val _queueSnapshot = MutableStateFlow(QueueSnapshot())
    val queueSnapshot: StateFlow<QueueSnapshot> = _queueSnapshot.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(applicationContext)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildMediaNotification(null, false))

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(playerListener) }

        player = exo

        mediaSession = MediaSession.Builder(this, exo)
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

    private fun publishSnapshot() {
        val snap = QueueSnapshot(
            hotQueue = hotQueue.toList(),
            coldQueue = coldQueue.toList(),
            coldOriginal = coldOriginal,
            lane = lane,
            indexInLane = indexInLane,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
        _queueSnapshot.value = snap
        _nowPlaying.value = snap.currentSong
    }

    private fun currentSong(): Song? = when (lane) {
        QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
        QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
    }

    private fun loadCurrent(startPositionMs: Long = 0L, autoPlay: Boolean = false) {
        val song = currentSong() ?: run {
            Log.i(TAG, "loadCurrent: no current song (lane=$lane index=$indexInLane)")
            player?.stop()
            publishSnapshot()
            updateForegroundNotification()
            return
        }

        Log.i(
            TAG,
            "loadCurrent: title='${song.displayTitle}' path=${song.path} uri=${song.contentUri} " +
                "lane=$lane index=$indexInLane startMs=$startPositionMs autoPlay=$autoPlay " +
                "repeat=$repeatMode shuffle=$shuffleEnabled queueSize=${hotQueue.size} coldSize=${coldQueue.size}"
        )
        Log.d(
            TAG,
            "loadCurrent detail: id=${song.id} mime=${song.mimeType} durationMs=${song.durationMs} " +
                "artist='${song.artist}' album='${song.album}' coldResume=$coldResumeIndex"
        )

        val item = toMediaItem(song)
        player?.apply {
            setMediaItem(item, startPositionMs)
            prepare()
            playWhenReady = autoPlay
        }
        publishSnapshot()
        updateForegroundNotification()
        persistState()
    }

    fun playSource(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (songs.isEmpty()) return
        Log.i(TAG, "playSource: ${songs.size} tracks startIndex=$startIndex")
        coldOriginal = songs.toList()
        coldQueue.clear()
        if (shuffleEnabled) {
            coldQueue.addAll(songs.shuffled())
            val tapped = songs.getOrNull(startIndex)
            if (tapped != null) {
                coldQueue.removeAll { sameSong(it, tapped) }
                coldQueue.add(0, tapped)
            }
            indexInLane = 0
        } else {
            coldQueue.addAll(songs)
            indexInLane = startIndex.coerceIn(0, coldQueue.lastIndex)
        }
        coldResumeIndex = indexInLane
        lane = QueueLane.COLD
        loadCurrent(0L, autoPlay)
    }

    fun addToHotQueue(song: Song) {
        Log.i(TAG, "queue add: path=${song.path} title='${song.displayTitle}' (size→${hotQueue.size + 1})")
        hotQueue.add(song)
        publishSnapshot()
        persistState()
    }

    fun addToHotQueue(songs: List<Song>) {
        hotQueue.addAll(songs)
        publishSnapshot()
        persistState()
    }

    fun removeFromHot(index: Int) {
        if (index !in hotQueue.indices) return
        val removingCurrent = lane == QueueLane.HOT && index == indexInLane
        hotQueue.removeAt(index)
        if (lane == QueueLane.HOT) {
            when {
                hotQueue.isEmpty() -> resumeColdAfterQueue(removingCurrent)
                index < indexInLane -> {
                    indexInLane--
                    publishSnapshot()
                }
                removingCurrent -> {
                    indexInLane = indexInLane.coerceAtMost(hotQueue.lastIndex)
                    loadCurrent(0L, player?.playWhenReady == true)
                }
                else -> publishSnapshot()
            }
        } else {
            publishSnapshot()
        }
        persistState()
    }

    fun removeFromCold(index: Int) {
        if (index !in coldQueue.indices) return
        val removingCurrent = lane == QueueLane.COLD && index == indexInLane
        val song = coldQueue.removeAt(index)
        coldOriginal = coldOriginal.filterNot { sameSong(it, song) }
        if (index < coldResumeIndex) coldResumeIndex = (coldResumeIndex - 1).coerceAtLeast(0)
        if (lane == QueueLane.COLD) {
            when {
                coldQueue.isEmpty() -> {
                    indexInLane = -1
                    if (removingCurrent) {
                        player?.stop()
                        publishSnapshot()
                    } else publishSnapshot()
                }
                index < indexInLane -> {
                    indexInLane--
                    publishSnapshot()
                }
                removingCurrent -> loadCurrent(0L, player?.playWhenReady == true)
                else -> publishSnapshot()
            }
        } else {
            publishSnapshot()
        }
        persistState()
    }

    fun moveHot(from: Int, to: Int) {
        if (from !in hotQueue.indices || to !in hotQueue.indices || from == to) return
        val item = hotQueue.removeAt(from)
        hotQueue.add(to, item)
        if (lane == QueueLane.HOT) {
            indexInLane = when {
                indexInLane == from -> to
                from < indexInLane && to >= indexInLane -> indexInLane - 1
                from > indexInLane && to <= indexInLane -> indexInLane + 1
                else -> indexInLane
            }
        }
        publishSnapshot()
        persistState()
    }

    fun moveCold(from: Int, to: Int) {
        if (from !in coldQueue.indices || to !in coldQueue.indices || from == to) return
        val item = coldQueue.removeAt(from)
        coldQueue.add(to, item)
        if (lane == QueueLane.COLD) {
            indexInLane = when {
                indexInLane == from -> to
                from < indexInLane && to >= indexInLane -> indexInLane - 1
                from > indexInLane && to <= indexInLane -> indexInLane + 1
                else -> indexInLane
            }
        }
        publishSnapshot()
        persistState()
    }

    fun playQueueItem(laneTarget: QueueLane, index: Int) {
        when (laneTarget) {
            QueueLane.HOT -> {
                if (index !in hotQueue.indices) return
                if (lane == QueueLane.COLD) {
                    coldResumeIndex = indexInLane + 1
                }
                lane = QueueLane.HOT
                indexInLane = index
            }
            QueueLane.COLD -> {
                if (index !in coldQueue.indices) return
                lane = QueueLane.COLD
                indexInLane = index
                coldResumeIndex = index
            }
        }
        loadCurrent(0L, autoPlay = true)
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        Log.i(TAG, "setShuffle: $enabled")
        val current = currentSong()
        shuffleEnabled = enabled
        if (enabled) {
            val shuffled = coldOriginal.shuffled().toMutableList()
            if (current != null && lane == QueueLane.COLD) {
                shuffled.removeAll { sameSong(it, current) }
                shuffled.add(0, current)
                coldQueue.clear()
                coldQueue.addAll(shuffled)
                indexInLane = 0
                coldResumeIndex = 0
            } else {
                coldQueue.clear()
                coldQueue.addAll(shuffled)
            }
        } else {
            coldQueue.clear()
            coldQueue.addAll(coldOriginal)
            if (current != null && lane == QueueLane.COLD) {
                val idx = coldQueue.indexOfFirst { sameSong(it, current) }
                indexInLane = if (idx >= 0) idx else 0
                coldResumeIndex = indexInLane
            }
        }
        publishSnapshot()
        persistState()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.COLD
            RepeatMode.COLD -> RepeatMode.OFF
        }
        Log.i(TAG, "cycleRepeatMode -> $repeatMode")
        publishSnapshot()
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        Log.i(TAG, "setRepeatMode -> $mode")
        repeatMode = mode
        publishSnapshot()
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

    fun skipToNext() = advance(userInitiated = true)

    fun skipToPrevious() {
        val p = player
        if (p != null && p.currentPosition > 3000L) {
            p.seekTo(0)
            persistState()
            return
        }
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane > 0) {
                    indexInLane--
                    loadCurrent(0L, autoPlay = true)
                } else {
                    // Back into context just before resume point
                    val prevCold = (coldResumeIndex - 1).coerceAtLeast(0)
                    if (coldQueue.isNotEmpty()) {
                        lane = QueueLane.COLD
                        indexInLane = prevCold
                        loadCurrent(0L, autoPlay = true)
                    } else {
                        p?.seekTo(0)
                    }
                }
            }
            QueueLane.COLD -> {
                if (indexInLane > 0) {
                    indexInLane--
                    loadCurrent(0L, autoPlay = true)
                } else {
                    p?.seekTo(0)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        persistState()
    }

    /**
     * Spotify-style next:
     * - Repeat one (natural end only): reload same track
     * - If user queue has items and we're on context: jump to queue, remember cold resume
     * - Drain user queue in order, then return to context at coldResumeIndex
     * - Otherwise advance context; Repeat all loops context
     */
    private fun advance(userInitiated: Boolean) {
        if (advancing) {
            Log.d(TAG, "advance ignored (already advancing)")
            return
        }
        advancing = true
        try {
            Log.i(
                TAG,
                "advance user=$userInitiated repeat=$repeatMode lane=$lane index=$indexInLane " +
                    "queue=${hotQueue.size} cold=${coldQueue.size} resume=$coldResumeIndex " +
                    "path=${currentSong()?.path}"
            )

            if (repeatMode == RepeatMode.ONE && !userInitiated) {
                Log.i(TAG, "repeat ONE → reload path=${currentSong()?.path}")
                loadCurrent(0L, autoPlay = true)
                return
            }

            when (lane) {
                QueueLane.HOT -> {
                    // Finished a queued track → next in queue or back to context
                    if (indexInLane < hotQueue.lastIndex) {
                        indexInLane++
                        Log.i(TAG, "queue next → index=$indexInLane path=${hotQueue.getOrNull(indexInLane)?.path}")
                        loadCurrent(0L, autoPlay = true)
                    } else {
                        Log.i(TAG, "queue drained → resume context at $coldResumeIndex")
                        // Drop consumed queue entries for a clean slate
                        hotQueue.clear()
                        resumeColdAfterQueue(play = true)
                    }
                }
                QueueLane.COLD -> {
                    // User queue takes priority for "next"
                    if (hotQueue.isNotEmpty()) {
                        coldResumeIndex = (indexInLane + 1).coerceAtMost(coldQueue.size)
                        lane = QueueLane.HOT
                        indexInLane = 0
                        Log.i(
                            TAG,
                            "context → queue first item path=${hotQueue.firstOrNull()?.path} " +
                                "(resume cold at $coldResumeIndex)"
                        )
                        loadCurrent(0L, autoPlay = true)
                    } else if (indexInLane < coldQueue.lastIndex) {
                        indexInLane++
                        coldResumeIndex = indexInLane
                        loadCurrent(0L, autoPlay = true)
                    } else if (repeatMode == RepeatMode.COLD && coldQueue.isNotEmpty()) {
                        Log.i(TAG, "repeat ALL → restart context")
                        indexInLane = 0
                        coldResumeIndex = 0
                        loadCurrent(0L, autoPlay = true)
                    } else {
                        Log.i(TAG, "end of context")
                        player?.pause()
                        publishSnapshot()
                        updateForegroundNotification()
                    }
                }
            }
        } finally {
            advancing = false
        }
    }

    private fun resumeColdAfterQueue(play: Boolean) {
        lane = QueueLane.COLD
        if (coldQueue.isEmpty()) {
            indexInLane = -1
            player?.pause()
            publishSnapshot()
            updateForegroundNotification()
            return
        }
        indexInLane = coldResumeIndex.coerceIn(0, coldQueue.lastIndex)
        // If resume is past the end, stop or loop
        if (coldResumeIndex >= coldQueue.size) {
            if (repeatMode == RepeatMode.COLD) {
                indexInLane = 0
                coldResumeIndex = 0
                loadCurrent(0L, autoPlay = play)
            } else {
                indexInLane = coldQueue.lastIndex
                player?.pause()
                publishSnapshot()
                updateForegroundNotification()
            }
            return
        }
        loadCurrent(0L, autoPlay = play)
    }

    private fun rebuildColdFromOriginal() {
        coldQueue.clear()
        if (shuffleEnabled) coldQueue.addAll(coldOriginal.shuffled())
        else coldQueue.addAll(coldOriginal)
    }

    private fun sameSong(a: Song, b: Song): Boolean {
        if (a.path != null && b.path != null) return a.path == b.path
        return a.contentUri == b.contentUri || a.id == b.id
    }

    private fun toMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
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
    }

    private fun restorePlaybackState() {
        if (restoredOnce) return
        restoredOnce = true
        serviceScope.launch {
            val saved = withContext(Dispatchers.IO) { stateStore.load() } ?: return@launch
            hotQueue.clear()
            hotQueue.addAll(saved.snapshot.hotQueue)
            coldOriginal = saved.snapshot.coldOriginal.ifEmpty { saved.snapshot.coldQueue }
            coldQueue.clear()
            coldQueue.addAll(saved.snapshot.coldQueue)
            lane = saved.snapshot.lane
            indexInLane = saved.snapshot.indexInLane
            shuffleEnabled = saved.snapshot.shuffleEnabled
            repeatMode = saved.snapshot.repeatMode
            coldResumeIndex = when (lane) {
                QueueLane.COLD -> indexInLane
                QueueLane.HOT -> indexInLane // best effort
            }

            val max = when (lane) {
                QueueLane.HOT -> hotQueue.lastIndex
                QueueLane.COLD -> coldQueue.lastIndex
            }
            if (max < 0) return@launch
            indexInLane = indexInLane.coerceIn(0, max)

            Log.i(TAG, "restorePlaybackState lane=$lane index=$indexInLane repeat=$repeatMode")
            loadCurrent(saved.positionMs, autoPlay = false)
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
        val p = player
        stateStore.save(
            snapshot = _queueSnapshot.value,
            positionMs = p?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            playWhenReady = p?.playWhenReady == true
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
            Log.d(TAG, "onPlaybackStateChanged $name path=${currentSong()?.path}")
            if (playbackState == Player.STATE_ENDED) {
                advance(userInitiated = false)
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
            this,
            REQUEST_OPEN_PLAYER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionPending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaNotification(song: Song?, playing: Boolean): Notification {
        val title = song?.displayTitle ?: "Yuri Player"
        val text = song?.displayArtist ?: if (playing) "Playing" else "Paused"

        val prev = serviceActionPending(ACTION_PREV, REQUEST_PREV)
        val toggle = serviceActionPending(ACTION_TOGGLE, REQUEST_TOGGLE)
        val next = serviceActionPending(ACTION_NEXT, REQUEST_NEXT)

        val playPauseIcon =
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (playing) "Pause" else "Play"

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
            .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
            .addAction(playPauseIcon, playPauseLabel, toggle)
            .addAction(android.R.drawable.ic_media_next, "Next", next)

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
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
    fun getCurrentSong(): Song? = currentSong()
    fun getPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun getQueueSnapshot(): QueueSnapshot = _queueSnapshot.value

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playSource(songs, startIndex, autoPlay = false)
    }

    fun getQueue(): List<Song> = _queueSnapshot.value.flatQueue
    fun getCurrentIndex(): Int {
        val snap = _queueSnapshot.value
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
