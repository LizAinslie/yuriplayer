package capital.yuri.yuriplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.isNetworkUri
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
import capital.yuri.yuriplayer.player.radio.RadioSourcePrefs
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
import kotlinx.coroutines.yield
import org.koin.android.ext.android.inject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Foreground playback service.
 *
 * Audio is owned by the user-selected [PlaybackEngine] (Media3 or LibVLC).
 * MediaSession is [EngineSessionBridge] — independent of any specific backend.
 * This class owns queue policy, restore, notification, and stall recovery.
 */
class MusicService : Service() {

    private val binder = LocalBinder()
    private val queueManager: QueueManager by inject()
    private val stateStore: PlaybackStateStore by inject()
    private val historyStore: PlaybackHistoryStore by inject()
    private val librarySettings: LibrarySettings by inject()

    private var engineHooks: MusicServiceEngineHooks? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var stallWatchJob: Job? = null
    private var restoredOnce = false
    private var advancing = false
    private var recoveringAudio = false
    private var lastHistoryKey: String? = null

    private var stallSamplePos = -1L
    private var stallSampleAtElapsed = 0L

    private var stickySeekTargetMs: Long = -1L
    private var stickySeekUntilElapsed: Long = 0L
    private var userSeekGuardUntilElapsed: Long = 0L

    private var pendingRemoteRestore: PendingRemoteRestore? = null

    private data class PendingRemoteRestore(
        val positionMs: Long,
        val wasPlayWhenReady: Boolean
    )

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val queueSnapshot: StateFlow<QueueSnapshot> get() = queueManager.snapshot
    val historyEntries: StateFlow<List<HistoryEntry>> get() = historyStore.entries

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildMediaNotification(null, false))

        engineHooks = MusicServiceEngineHooks(
            context = this,
            settings = librarySettings,
            sessionActivity = openPlayerPendingIntent(),
            onPlay = { play() },
            onPause = { pause() },
            onNext = { skipToNext() },
            onPrev = { skipToPrevious(forceTrackChange = false) },
            onSeek = { pos -> seekTo(pos) },
            onEnded = { onEngineEnded() },
            onPlayingChanged = { playing ->
                _isPlaying.value = playing
                updateForegroundNotification()
                persistState()
            }
        )

        Log.i(TAG, "engine=${engineHooks?.engineId}")
        restorePlaybackState()
        startPeriodicPersist()
        startStallWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> skipToNext()
            ACTION_PREV -> skipToPrevious(forceTrackChange = false)
            else -> startForeground(
                NOTIFICATION_ID,
                buildMediaNotification(_nowPlaying.value, _isPlaying.value)
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isRepeatOne(): Boolean =
        queueManager.getSnapshot().repeatMode == RepeatMode.ONE

    private fun inUserSeekGuard(): Boolean =
        SystemClock.elapsedRealtime() < userSeekGuardUntilElapsed

    private fun isRemoteSong(song: Song?): Boolean {
        if (song == null) return false
        if (isVirtualLibraryPath(song.path)) return true
        return isNetworkUri(MusicServicePlaybackHooks.songUri(song))
    }

    private fun clearStickySeek() {
        stickySeekTargetMs = -1L
        stickySeekUntilElapsed = 0L
    }

    private fun flushPendingRemoteRestore(autoPlay: Boolean): Boolean {
        val pending = pendingRemoteRestore ?: return false
        pendingRemoteRestore = null
        Log.i(TAG, "flushPendingRemoteRestore pos=${pending.positionMs} autoPlay=$autoPlay")
        rebufferWindow(pending.positionMs, autoPlay = autoPlay, forceReload = true)
        return true
    }

    private fun hardRestartCurrent(autoPlay: Boolean = true, startPositionMs: Long = 0L) {
        val current = queueManager.currentSong() ?: return
        clearStickySeek()
        pendingRemoteRestore = null
        Log.i(TAG, "hardRestartCurrent '${current.displayTitle}' pos=$startPositionMs")
        rebufferWindow(startPositionMs.coerceAtLeast(0L), autoPlay = autoPlay, forceReload = true)
    }

    private fun rebufferWindow(
        startPositionMs: Long = 0L,
        autoPlay: Boolean = false,
        forceReload: Boolean = false
    ) {
        val hooks = engineHooks ?: return
        val current = queueManager.currentSong()
        if (current == null) {
            hooks.deactivate()
            _nowPlaying.value = null
            pendingRemoteRestore = null
            updateForegroundNotification()
            return
        }

        clearStickySeek()
        pendingRemoteRestore = null

        val repeatOne = isRepeatOne()
        val nextSong = if (repeatOne) null else queueManager.peekNext()

        // Skip reload if same track is already loaded and position is close
        if (!forceReload &&
            hooks.active &&
            _nowPlaying.value?.id == current.id
        ) {
            val pos = hooks.getPositionMs()
            if (startPositionMs > 0L && abs(pos - startPositionMs) > 400L) {
                hooks.seekTo(startPositionMs)
            }
            if (autoPlay && !hooks.isPlaying()) hooks.play()
            _nowPlaying.value = current
            maybeRecordHistory(current)
            hooks.updateMetadata(current)
            updateForegroundNotification()
            return
        }

        Log.i(
            TAG,
            "rebufferWindow engine=${hooks.engineId} current='${current.displayTitle}' " +
                "next='${if (repeatOne) "(repeat-one)" else nextSong?.displayTitle}' " +
                "startMs=$startPositionMs autoPlay=$autoPlay force=$forceReload " +
                "remote=${isRemoteSong(current)}"
        )

        try {
            hooks.playWindow(
                song = current,
                next = nextSong,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = autoPlay
            )
        } catch (e: Exception) {
            Log.e(TAG, "rebufferWindow failed", e)
        }

        _nowPlaying.value = current
        maybeRecordHistory(current)
        updateForegroundNotification()
        persistState()
    }

    private fun updateNextMediaItemOnly() {
        // Engines hold a 1–2 item window; reload next peek without seeking away
        val hooks = engineHooks ?: return
        val current = queueManager.currentSong() ?: return
        if (pendingRemoteRestore != null) return
        if (!hooks.active) {
            rebufferWindow(0L, autoPlay = hooks.getPlayWhenReady(), forceReload = true)
            return
        }
        val pos = hooks.getPositionMs()
        val playing = hooks.getPlayWhenReady()
        rebufferWindow(pos, autoPlay = playing, forceReload = true)
    }

    private fun maybeRecordHistory(song: Song) {
        val key = song.path ?: song.contentUri.toString()
        if (key == lastHistoryKey) return
        lastHistoryKey = key
        historyStore.record(song)
    }

    private fun applyAdvance(result: QueueManager.AdvanceResult, autoPlay: Boolean = true) {
        clearStickySeek()
        pendingRemoteRestore = null
        when {
            result.finished -> {
                engineHooks?.pause()
                _nowPlaying.value = queueManager.currentSong()
                updateForegroundNotification()
                persistState()
            }
            result.seekToStart -> hardRestartCurrent(autoPlay = autoPlay)
            result.reload -> hardRestartCurrent(autoPlay = autoPlay)
            result.song != null -> {
                val target = result.song
                _nowPlaying.value = target
                maybeRecordHistory(target)
                updateForegroundNotification()
                rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
            }
        }
    }

    private fun onEngineEnded() {
        if (advancing) return

        if (inUserSeekGuard()) {
            val target = stickySeekTargetMs.takeIf { it >= 0L }
                ?: engineHooks?.getPositionMs() ?: 0L
            Log.i(TAG, "ENDED suppressed after user seek → reseek $target")
            rebufferWindow(target, autoPlay = true, forceReload = true)
            return
        }

        if (isRepeatOne()) {
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

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        source: ColdSource? = null
    ) {
        pendingRemoteRestore = null
        queueManager.playSource(songs, startIndex, source)
        rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String) {
        if (queueManager.updateColdFromSource(songs, sourceId)) {
            updateNextMediaItemOnly()
            persistState()
        }
    }

    fun applyRadioPrefs(prefs: RadioSourcePrefs) {
        queueManager.applyRadioPrefs(prefs)
        updateNextMediaItemOnly()
        persistState()
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
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            updateNextMediaItemOnly()
        }
        persistState()
    }

    fun removeFromCold(index: Int) {
        val needReload = queueManager.removeFromContext(index)
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            updateNextMediaItemOnly()
        }
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
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            updateNextMediaItemOnly()
        }
        persistState()
    }

    fun playQueueItem(lane: QueueLane, index: Int) {
        pendingRemoteRestore = null
        queueManager.playItem(lane, index)
        rebufferWindow(0L, autoPlay = true, forceReload = true)
    }

    fun setShuffle(enabled: Boolean) {
        queueManager.setShuffle(enabled)
        updateNextMediaItemOnly()
        persistState()
    }

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
        if (flushPendingRemoteRestore(autoPlay = true)) {
            updateForegroundNotification()
            persistState()
            return
        }
        val hooks = engineHooks
        if (hooks == null || !hooks.active) {
            rebufferWindow(0L, autoPlay = true, forceReload = true)
        } else {
            hooks.play()
        }
        updateForegroundNotification()
        persistState()
    }

    fun pause() {
        engineHooks?.pause()
        updateForegroundNotification()
        persistState()
    }

    fun togglePlayPause() {
        val hooks = engineHooks
        if (hooks != null && !hooks.isPlaying() && pendingRemoteRestore != null) {
            play()
            return
        }
        if (hooks == null || !hooks.active) {
            play()
            return
        }
        if (hooks.isPlaying()) hooks.pause() else hooks.play()
        updateForegroundNotification()
        persistState()
    }

    fun skipToNext() {
        if (advancing) return
        userSeekGuardUntilElapsed = 0L
        clearStickySeek()
        advancing = true
        try {
            applyAdvance(queueManager.advance(userInitiated = true))
        } finally {
            advancing = false
        }
    }

    fun skipToPrevious(forceTrackChange: Boolean = false) {
        userSeekGuardUntilElapsed = 0L
        clearStickySeek()
        applyAdvance(
            queueManager.skipPrevious(
                currentPositionMs = engineHooks?.getPositionMs() ?: 0L,
                forceTrackChange = forceTrackChange
            )
        )
    }

    fun seekTo(positionMs: Long) {
        if (pendingRemoteRestore != null) {
            val pending = pendingRemoteRestore!!
            pendingRemoteRestore = pending.copy(positionMs = positionMs.coerceAtLeast(0L))
            persistState()
            return
        }
        val hooks = engineHooks ?: return
        if (!hooks.active) return

        val playerDuration = hooks.getDurationMs().takeIf { it > 0 } ?: 0L
        val metaDuration = queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
        val duration = when {
            playerDuration > 0L -> playerDuration
            metaDuration > 0L -> metaDuration
            else -> 0L
        }

        val target = when {
            duration <= 0L -> positionMs.coerceAtLeast(0L)
            positionMs >= duration -> (duration - 1L).coerceAtLeast(0L)
            positionMs < 0L -> 0L
            else -> positionMs
        }

        val now = SystemClock.elapsedRealtime()
        stickySeekTargetMs = target
        stickySeekUntilElapsed = now + STICKY_SEEK_MS
        userSeekGuardUntilElapsed = now + USER_SEEK_GUARD_MS
        stallSamplePos = target
        stallSampleAtElapsed = now

        try {
            hooks.seekTo(target)
            if (hooks.getPlayWhenReady() && !hooks.isPlaying()) hooks.play()
            Log.i(TAG, "seekTo target=$target (raw=$positionMs) duration=$duration")
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed", e)
            clearStickySeek()
        }
        persistState()
    }

    fun seekToFraction(fraction: Float) {
        val metaDuration = queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
        val playerDuration = engineHooks?.getDurationMs()?.takeIf { it > 0 } ?: 0L
        val duration = when {
            playerDuration > 0L -> playerDuration
            metaDuration > 0L -> metaDuration
            else -> 0L
        }
        if (duration <= 0L) return
        val f = fraction.toDouble().coerceIn(0.0, 1.0)
        seekTo((f * duration.toDouble()).roundToLong())
    }

    fun peekNext(): Song? = queueManager.peekNext()
    fun peekPrevious(): Song? = queueManager.peekPrevious()

    fun clearHistory() = historyStore.clear()
    fun getHistory(): List<HistoryEntry> = historyStore.entries
    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
    }

    private fun restorePlaybackState() {
        if (restoredOnce) return
        restoredOnce = true
        serviceScope.launch {
            val saved = withContext(Dispatchers.IO) { stateStore.load() } ?: return@launch
            queueManager.restore(saved.snapshot)
            val current = queueManager.currentSong()
            _nowPlaying.value = current
            updateForegroundNotification()
            yield()
            if (isRemoteSong(current)) {
                pendingRemoteRestore = PendingRemoteRestore(
                    positionMs = saved.positionMs,
                    wasPlayWhenReady = saved.playWhenReady
                )
                Log.i(
                    TAG,
                    "restore deferred remote '${current?.displayTitle}' " +
                        "pos=${saved.positionMs} wasPlaying=${saved.playWhenReady}"
                )
            } else {
                delay(RESTORE_PREPARE_DELAY_MS)
                rebufferWindow(saved.positionMs, autoPlay = false, forceReload = true)
            }
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

    private fun startStallWatchdog() {
        stallWatchJob?.cancel()
        stallWatchJob = serviceScope.launch {
            while (isActive) {
                delay(STALL_POLL_MS)
                if (pendingRemoteRestore != null) {
                    stallSamplePos = -1L
                    continue
                }
                val hooks = engineHooks ?: continue
                if (!hooks.active || recoveringAudio || advancing || inUserSeekGuard()) {
                    stallSamplePos = -1L
                    continue
                }
                if (!hooks.getPlayWhenReady()) {
                    stallSamplePos = -1L
                    continue
                }

                val pos = hooks.getPositionMs().coerceAtLeast(0L)
                val duration = hooks.getDurationMs().takeIf { it > 0 } ?: 0L
                if (duration > 0L && pos >= duration - NEAR_END_MS) {
                    stallSamplePos = pos
                    stallSampleAtElapsed = SystemClock.elapsedRealtime()
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                if (pos != stallSamplePos) {
                    stallSamplePos = pos
                    stallSampleAtElapsed = now
                    continue
                }

                val frozenFor = now - stallSampleAtElapsed
                val looksStuck = frozenFor >= STALL_MS &&
                    (hooks.isPlaying() || hooks.getPlayWhenReady())
                if (looksStuck && stallSamplePos >= 0L) {
                    Log.w(
                        TAG,
                        "stall watchdog: pos frozen at $pos for ${frozenFor}ms — recovering"
                    )
                    recoverFromAudioGlitch(atPositionMs = pos)
                }
            }
        }
    }

    private fun persistState() {
        val pending = pendingRemoteRestore
        stateStore.save(
            snapshot = queueManager.getSnapshot(),
            positionMs = if (pending != null) pending.positionMs else getPositionMs(),
            playWhenReady = if (pending != null) false else engineHooks?.getPlayWhenReady() == true
        )
    }

    private fun recoverFromAudioGlitch(atPositionMs: Long? = null) {
        if (recoveringAudio) return
        recoveringAudio = true
        serviceScope.launch {
            try {
                val hooks = engineHooks
                val pos = (atPositionMs ?: hooks?.getPositionMs() ?: 0L).coerceAtLeast(0L)
                val wasPlaying = hooks?.getPlayWhenReady() == true || hooks?.isPlaying() == true
                Log.w(TAG, "audio glitch → rebuffer at $pos autoPlay=$wasPlaying")
                rebufferWindow(pos, autoPlay = wasPlaying, forceReload = true)
            } finally {
                delay(600)
                recoveringAudio = false
                stallSamplePos = -1L
                stallSampleAtElapsed = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun updateForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildMediaNotification(_nowPlaying.value, _isPlaying.value))
        engineHooks?.updateMetadata(_nowPlaying.value)
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

        // Platform MediaSession token (works for Media3 and VLC)
        engineHooks?.let { hooks ->
            try {
                @Suppress("DEPRECATION")
                builder.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(
                            android.support.v4.media.session.MediaSessionCompat.Token
                                .fromToken(hooks.sessionToken())
                        )
                        .setShowActionsInCompactView(0, 1, 2)
                )
            } catch (e: Throwable) {
                // media artifact optional at compile; actions still work
                Log.d(TAG, "MediaStyle skipped: ${e.message}")
            }
        }
        return builder.build()
    }

    fun isPlaying(): Boolean = engineHooks?.isPlaying() == true
    fun getCurrentSong(): Song? = _nowPlaying.value ?: queueManager.currentSong()

    fun getPositionMs(): Long {
        pendingRemoteRestore?.let { return it.positionMs }
        val real = engineHooks?.getPositionMs()?.coerceAtLeast(0L) ?: 0L
        val now = SystemClock.elapsedRealtime()
        if (stickySeekTargetMs >= 0L && now < stickySeekUntilElapsed) {
            if (abs(real - stickySeekTargetMs) <= SEEK_CONFIRM_MS) {
                clearStickySeek()
                return real
            }
            return stickySeekTargetMs
        }
        if (stickySeekTargetMs >= 0L) clearStickySeek()
        return real
    }

    fun getDurationMs(): Long {
        if (pendingRemoteRestore != null) {
            return queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
        }
        val d = engineHooks?.getDurationMs() ?: 0L
        if (d > 0L) return d
        return queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
    }

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
            engineHooks?.pause()
            engineHooks?.deactivate()
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
        stallWatchJob?.cancel()
        serviceScope.cancel()
        engineHooks?.release()
        engineHooks = null
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
        private const val STALL_POLL_MS = 500L
        private const val STALL_MS = 2_000L
        private const val NEAR_END_MS = 500L
        private const val STICKY_SEEK_MS = 1_200L
        private const val USER_SEEK_GUARD_MS = 1_000L
        private const val SEEK_CONFIRM_MS = 600L
        private const val RESTORE_PREPARE_DELAY_MS = 40L
    }
}
