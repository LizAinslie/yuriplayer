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

    // Dual queue
    private val hotQueue = mutableListOf<Song>()
    private var coldOriginal: List<Song> = emptyList()
    private val coldQueue = mutableListOf<Song>()
    private var lane = QueueLane.COLD
    private var indexInLane = -1
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF

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
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification("Yuri Player", "Starting…"))

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

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()

        restorePlaybackState()
        startPeriodicPersist()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val song = _nowPlaying.value
        startForeground(
            NOTIFICATION_ID,
            buildPlaceholderNotification(
                song?.displayTitle ?: "Yuri Player",
                song?.displayArtist ?: "Playing in background"
            )
        )
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
            player?.stop()
            publishSnapshot()
            updateForegroundNotification()
            return
        }
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

    /** Replace cold queue with a source list (album / playlist) and start playing. Hot queue kept. */
    fun playSource(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (songs.isEmpty()) return
        coldOriginal = songs.toList()
        coldQueue.clear()
        if (shuffleEnabled) {
            coldQueue.addAll(songs.shuffled())
            // Prefer starting with the tapped song even when shuffled
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
        lane = QueueLane.COLD
        // If hot queue has items, user might still want them first — only jump to cold when playing a source
        // Spec: hot plays before cold when advancing; starting a source plays that source now.
        loadCurrent(0L, autoPlay)
    }

    fun addToHotQueue(song: Song) {
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
                hotQueue.isEmpty() -> {
                    lane = QueueLane.COLD
                    indexInLane = indexInLane.coerceAtMost(coldQueue.lastIndex)
                    if (removingCurrent) loadCurrent(0L, player?.playWhenReady == true)
                    else publishSnapshot()
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

    fun removeFromCold(index: Int) {
        if (index !in coldQueue.indices) return
        val removingCurrent = lane == QueueLane.COLD && index == indexInLane
        val song = coldQueue.removeAt(index)
        // Keep original in sync by id/path when possible
        coldOriginal = coldOriginal.filterNot { sameSong(it, song) }
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
        // Manual reorder while shuffled: treat current cold order as the play order;
        // original order still used when shuffle is turned off.
        publishSnapshot()
        persistState()
    }

    fun playQueueItem(laneTarget: QueueLane, index: Int) {
        when (laneTarget) {
            QueueLane.HOT -> {
                if (index !in hotQueue.indices) return
                lane = QueueLane.HOT
                indexInLane = index
            }
            QueueLane.COLD -> {
                if (index !in coldQueue.indices) return
                lane = QueueLane.COLD
                indexInLane = index
            }
        }
        loadCurrent(0L, autoPlay = true)
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
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
            } else {
                coldQueue.clear()
                coldQueue.addAll(shuffled)
            }
        } else {
            // Restore original source order in place
            coldQueue.clear()
            coldQueue.addAll(coldOriginal)
            if (current != null && lane == QueueLane.COLD) {
                val idx = coldQueue.indexOfFirst { sameSong(it, current) }
                indexInLane = if (idx >= 0) idx else 0
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
        publishSnapshot()
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
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
                    p?.seekTo(0)
                }
            }
            QueueLane.COLD -> {
                if (indexInLane > 0) {
                    indexInLane--
                    loadCurrent(0L, autoPlay = true)
                } else if (hotQueue.isNotEmpty()) {
                    // Jump to end of hot queue
                    lane = QueueLane.HOT
                    indexInLane = hotQueue.lastIndex
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

    private fun advance(userInitiated: Boolean) {
        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            player?.seekTo(0)
            player?.play()
            return
        }

        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane < hotQueue.lastIndex) {
                    indexInLane++
                    loadCurrent(0L, autoPlay = true)
                } else if (coldQueue.isNotEmpty()) {
                    lane = QueueLane.COLD
                    indexInLane = 0
                    loadCurrent(0L, autoPlay = true)
                } else if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
                    // No cold currently but original exists — rebuild
                    rebuildColdFromOriginal()
                    lane = QueueLane.COLD
                    indexInLane = 0
                    loadCurrent(0L, autoPlay = true)
                } else {
                    // End of everything
                    player?.pause()
                    publishSnapshot()
                }
            }
            QueueLane.COLD -> {
                if (indexInLane < coldQueue.lastIndex) {
                    indexInLane++
                    loadCurrent(0L, autoPlay = true)
                } else if (repeatMode == RepeatMode.COLD && coldQueue.isNotEmpty()) {
                    indexInLane = 0
                    loadCurrent(0L, autoPlay = true)
                } else {
                    player?.pause()
                    publishSnapshot()
                }
            }
        }
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

            // Clamp index
            val max = when (lane) {
                QueueLane.HOT -> hotQueue.lastIndex
                QueueLane.COLD -> coldQueue.lastIndex
            }
            if (max < 0) return@launch
            indexInLane = indexInLane.coerceIn(0, max)

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
            if (playbackState == Player.STATE_ENDED) {
                advance(userInitiated = false)
            }
        }
    }

    private fun updateForegroundNotification() {
        val song = _nowPlaying.value
        startForeground(
            NOTIFICATION_ID,
            buildPlaceholderNotification(
                song?.displayTitle ?: "Yuri Player",
                song?.displayArtist ?: if (_isPlaying.value) "Playing" else "Paused"
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildPlaceholderNotification(title: String, text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == null) binder else super.onBind(intent) ?: binder
    }

    // --- Compatibility / query helpers ---

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun getCurrentSong(): Song? = currentSong()
    fun getPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun getQueueSnapshot(): QueueSnapshot = _queueSnapshot.value

    /** @deprecated use playSource */
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
        const val CHANNEL_ID = "yuri_playback"
        const val NOTIFICATION_ID = 42
    }
}
