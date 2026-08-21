package capital.yuri.yuriplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.isNetworkUri
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
import capital.yuri.yuriplayer.player.engine.toPlaybackMedia
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
import kotlinx.coroutines.flow.drop
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
    private var persistDebounceJob: Job? = null
    private var stallWatchJob: Job? = null
    private var restoredOnce = false
    private var advancing = false
    private var recoveringAudio = false
    private var lastHistoryKey: String? = null
    private var windowGeneration: Int = 0
    private var endedForWindow: Int = -1
    /** Engine posts onAutoAdvanced after playPreparedNext; the queue already moved. */
    private var ignoreAutoAdvancedUntilElapsed: Long = 0L
    private var lastAdvanceElapsed: Long = 0L
    private var ignoreWatchdogUntilElapsed: Long = 0L
    /** System MediaSession often fires onPlay when the session becomes active. */
    private var ignoreSessionPlayUntilElapsed: Long = Long.MAX_VALUE

    private var stallSamplePos = -1L
    private var stallSampleAtElapsed = 0L

    private var stickySeekTargetMs: Long = -1L
    private var stickySeekUntilElapsed: Long = 0L
    private var userSeekGuardUntilElapsed: Long = 0L

    private var pendingRemoteRestore: PendingRemoteRestore? = null

    /** User/session wants audio. Engine hiccups must not clear this. */
    private var userWantsPlay = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private data class PendingRemoteRestore(
        val positionMs: Long,
        val wasPlayWhenReady: Boolean
    )

    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _viewState = MutableStateFlow(PlayerViewState())
    val viewState: StateFlow<PlayerViewState> = _viewState.asStateFlow()

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
            onPlay = {
                if (SystemClock.elapsedRealtime() < ignoreSessionPlayUntilElapsed) {
                    Log.i(TAG, "session onPlay ignored — startup")
                    return@MusicServiceEngineHooks
                }
                play()
            },
            onPause = { pause() },
            onNext = { skipToNext() },
            onPrev = { skipToPrevious(forceTrackChange = false) },
            onSeek = { pos -> seekTo(pos) },
            onEnded = { onEngineEnded() },
            onAutoAdvanced = { onEngineAutoAdvanced() },
            onPlayingChanged = { playing ->
                _isPlaying.value = playing
                updateForegroundNotification()
                if (playing) acquireSleepLocks()
                else if (!userWantsPlay) releaseSleepLocks()
            },
            onError = { message, recoverable ->
                Log.e(TAG, "engine error: $message recoverable=$recoverable")
                if (recoverable && userWantsPlay && !recoveringAudio && !advancing) {
                    recoverFromAudioGlitch()
                }
            }
        )

        Log.i(TAG, "engine=${engineHooks?.engineId}")
        restorePlaybackState()
        startPeriodicPersist()
        startStallWatchdog()
        serviceScope.launch {
            librarySettings.streamQuality.drop(1).collect { q ->
                Log.i(TAG, "stream quality → ${q.id} — rebuffer next")
                syncPreparedNext()
            }
        }
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
                "peekNext='${if (repeatOne) "(repeat-one)" else nextSong?.displayTitle}' " +
                "startMs=$startPositionMs autoPlay=$autoPlay force=$forceReload " +
                "remote=${isRemoteSong(current)}"
        )

        try {
            windowGeneration += 1
            endedForWindow = -1
            ignoreAutoAdvancedUntilElapsed = 0L
            lastAdvanceElapsed = SystemClock.elapsedRealtime()
            ignoreWatchdogUntilElapsed = lastAdvanceElapsed + WATCHDOG_GRACE_MS
            stallSamplePos = -1L
            if (autoPlay) {
                userWantsPlay = true
                acquireSleepLocks()
            }
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
                userWantsPlay = false
                releaseSleepLocks()
                engineHooks?.pause()
                _nowPlaying.value = queueManager.currentSong()
                updateForegroundNotification()
                persistState()
            }
            result.seekToStart -> {
                val hooks = engineHooks
                if (hooks != null && hooks.active) {
                    hooks.seekTo(0L)
                    if (autoPlay) {
                        userWantsPlay = true
                        if (!hooks.isPlaying()) hooks.play()
                    }
                    updateForegroundNotification()
                    persistState()
                } else {
                    hardRestartCurrent(autoPlay = autoPlay)
                }
            }
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

    /** Queue already matches the engine (pre-buffered next is now current). */
    private fun applyAdvanceKeepEngine(result: QueueManager.AdvanceResult) {
        clearStickySeek()
        pendingRemoteRestore = null
        when {
            result.finished -> {
                userWantsPlay = false
                releaseSleepLocks()
                engineHooks?.pause()
                _nowPlaying.value = queueManager.currentSong()
            }
            result.seekToStart -> engineHooks?.seekTo(0L)
            result.reload -> hardRestartCurrent(autoPlay = true)
            result.song != null -> {
                _nowPlaying.value = result.song
                maybeRecordHistory(result.song)
                syncPreparedNext()
            }
        }
        updateForegroundNotification()
        persistState()
    }

    private fun syncPreparedNext() {
        val hooks = engineHooks ?: return
        if (!hooks.active) return
        if (isRepeatOne()) {
            hooks.setNext(null)
            return
        }
        val next = queueManager.peekNext()
        Log.i(TAG, "syncPreparedNext '${next?.displayTitle}'")
        hooks.setNext(next)
    }

    /**
     * Claim the current engine window so a second EndReached / AUTO /
     * watchdog tick cannot advance the queue twice. Increments
     * [windowGeneration] so the *new* track is not treated as already ended.
     */
    private fun claimEndOfWindow(fromUser: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!fromUser && now - lastAdvanceElapsed < ADVANCE_DEBOUNCE_MS) return false
        if (!fromUser && endedForWindow == windowGeneration) return false
        lastAdvanceElapsed = now
        endedForWindow = windowGeneration
        windowGeneration += 1
        stallSamplePos = -1L
        ignoreWatchdogUntilElapsed = now + WATCHDOG_GRACE_MS
        return true
    }

    private fun onEngineAutoAdvanced() {
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreAutoAdvancedUntilElapsed) {
            ignoreAutoAdvancedUntilElapsed = 0L
            Log.i(TAG, "autoAdvanced ignored — queue already moved")
            return
        }
        if (advancing) return
        if (!claimEndOfWindow(fromUser = false)) return
        advancing = true
        try {
            val result = queueManager.advance(userInitiated = false)
            Log.i(TAG, "autoAdvanced → '${result.song?.displayTitle}'")
            applyAdvanceKeepEngine(result)
        } finally {
            advancing = false
        }
    }

    /**
     * Swap onto the already-prepared next item and advance queue state
     * without reloading. Used by skip, EndReached, and the stall watchdog.
     */
    private fun advanceUsingPreparedNext(userInitiated: Boolean): Boolean {
        val hooks = engineHooks ?: return false
        if (!hooks.hasPreparedNext()) return false
        val expected = queueManager.peekNext() ?: return false
        val preparedId = hooks.preparedNextId()
        val expectedId = expected.toPlaybackMedia(quality = librarySettings.getStreamQuality()).mediaId
        if (preparedId == null || preparedId != expectedId) {
            Log.i(
                TAG,
                "skip prepared mismatch want='${expected.displayTitle}' have=$preparedId"
            )
            syncPreparedNext()
            return false
        }
        // VLC swap does not post onAutoAdvanced; Media3 AUTO transition does.
        // Only ignore that callback for a short window so a later EndReached
        // is not swallowed (that left audio on the next track with old art).
        ignoreAutoAdvancedUntilElapsed =
            SystemClock.elapsedRealtime() + AUTO_ADVANCE_IGNORE_MS
        if (!hooks.playPreparedNext()) {
            ignoreAutoAdvancedUntilElapsed = 0L
            return false
        }
        claimEndOfWindow(fromUser = userInitiated)
        applyAdvanceKeepEngine(queueManager.advance(userInitiated = userInitiated))
        return true
    }

    private fun onEngineEnded() {
        if (advancing) return
        if (endedForWindow == windowGeneration) return

        if (inUserSeekGuard()) {
            val target = stickySeekTargetMs.takeIf { it >= 0L }
                ?: engineHooks?.getPositionMs() ?: 0L
            Log.i(TAG, "ENDED suppressed after user seek → reseek $target")
            rebufferWindow(target, autoPlay = true, forceReload = true)
            return
        }

        if (isRepeatOne()) {
            if (!claimEndOfWindow(fromUser = false)) return
            hardRestartCurrent(autoPlay = true)
            return
        }

        advancing = true
        try {
            if (advanceUsingPreparedNext(userInitiated = false)) {
                Log.i(TAG, "onEnded → playPreparedNext")
                return
            }
            if (!claimEndOfWindow(fromUser = false)) return
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
            persistState()
        }
    }

    fun applyRadioPrefs(prefs: RadioSourcePrefs) {
        queueManager.applyRadioPrefs(prefs)
        persistState()
    }

    fun addToHotQueue(song: Song) {
        queueManager.addToQueue(song)
        syncPreparedNext()
        persistState()
    }

    fun addToHotQueue(songs: List<Song>) {
        queueManager.addToQueue(songs)
        syncPreparedNext()
        persistState()
    }

    fun clearHotQueue() {
        queueManager.clearHotQueue()
        syncPreparedNext()
        persistState()
    }

    fun removeFromHot(index: Int) {
        val needReload = queueManager.removeFromQueue(index)
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            syncPreparedNext()
        }
        persistState()
    }

    fun removeFromCold(index: Int) {
        val needReload = queueManager.removeFromContext(index)
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            syncPreparedNext()
        }
        persistState()
    }

    fun moveHot(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        syncPreparedNext()
        persistState()
    }

    fun moveCold(from: Int, to: Int) {
        queueManager.moveInContext(from, to)
        syncPreparedNext()
        persistState()
    }

    fun moveColdToHot(index: Int) {
        val needReload = queueManager.moveColdToHot(index)
        if (needReload) {
            rebufferWindow(0L, autoPlay = engineHooks?.getPlayWhenReady() == true, forceReload = true)
        } else {
            syncPreparedNext()
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
        syncPreparedNext()
        persistState()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
        syncPreparedNext()
        persistState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        syncPreparedNext()
        persistState()
    }

    fun play() {
        userWantsPlay = true
        acquireSleepLocks()
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
        userWantsPlay = false
        releaseSleepLocks()
        engineHooks?.pause()
        updateForegroundNotification()
        persistState()
    }

    fun togglePlayPause() {
        val hooks = engineHooks
        if (hooks != null && hooks.isPlaying()) pause() else play()
    }

    fun skipToNext() {
        if (advancing) return
        userSeekGuardUntilElapsed = 0L
        clearStickySeek()
        advancing = true
        try {
            if (advanceUsingPreparedNext(userInitiated = true)) return
            applyAdvance(queueManager.advance(userInitiated = true))
        } finally {
            advancing = false
        }
    }

    fun skipToPrevious(forceTrackChange: Boolean = false) {
        userSeekGuardUntilElapsed = 0L
        clearStickySeek()
        advancing = true
        try {
            applyAdvance(
                queueManager.skipPrevious(
                    currentPositionMs = engineHooks?.getPositionMs() ?: 0L,
                    forceTrackChange = forceTrackChange
                )
            )
        } finally {
            advancing = false
        }
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
    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
    }

    private fun restorePlaybackState() {
        if (restoredOnce) return
        restoredOnce = true
        serviceScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { stateStore.load() } ?: return@launch
                queueManager.restore(saved.snapshot)
                val current = queueManager.currentSong()
                _nowPlaying.value = current
                updateForegroundNotification()
                yield()
                userWantsPlay = false
                if (isRemoteSong(current)) {
                    pendingRemoteRestore = PendingRemoteRestore(
                        positionMs = saved.positionMs,
                        wasPlayWhenReady = false
                    )
                    Log.i(
                        TAG,
                        "restore deferred remote '${current?.displayTitle}' pos=${saved.positionMs}"
                    )
                } else {
                    delay(RESTORE_PREPARE_DELAY_MS)
                    rebufferWindow(
                        saved.positionMs,
                        autoPlay = false,
                        forceReload = true
                    )
                    engineHooks?.pause()
                }
            } finally {
                userWantsPlay = false
                persistState(immediate = true)
                engineHooks?.pause()
                engineHooks?.updateMetadata(queueManager.currentSong())
                ignoreSessionPlayUntilElapsed =
                    SystemClock.elapsedRealtime() + STARTUP_IGNORE_SESSION_PLAY_MS
                engineHooks?.setSessionActive(true)
                Log.i(TAG, "restore complete — paused until user plays")
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
                publishViewState()
                if (pendingRemoteRestore != null) {
                    stallSamplePos = -1L
                    continue
                }
                val hooks = engineHooks ?: continue
                if (!hooks.active || recoveringAudio || advancing || inUserSeekGuard()) {
                    stallSamplePos = -1L
                    continue
                }
                if (SystemClock.elapsedRealtime() < ignoreWatchdogUntilElapsed) {
                    stallSamplePos = -1L
                    continue
                }
                if (userWantsPlay) acquireSleepLocks()

                val pos = hooks.getPositionMs().coerceAtLeast(0L)
                val engineDur = hooks.getDurationMs().takeIf { it > 0 } ?: 0L
                val metaDur = _nowPlaying.value?.durationMs?.takeIf { it > 0 } ?: 0L
                val duration = if (engineDur > 0L) engineDur else metaDur
                val live = hooks.isLive()
                val remaining = if (live || duration <= 0L) Long.MAX_VALUE else (duration - pos).coerceAtLeast(0L)
                val now = SystemClock.elapsedRealtime()
                val remote = isRemoteSong(_nowPlaying.value)
                val wantPlay = hooks.getPlayWhenReady()
                val playing = hooks.isPlaying()
                val buffering = hooks.isBuffering()
                val nearEnd = !live && duration > 0L && remaining <= NEAR_END_MS

                // Finite tracks only — live radio has no successor to warm.
                if (!live && wantPlay && remaining in 1L..WARMUP_NEXT_MS && !isRepeatOne()) {
                    hooks.warmupNext()
                }

                if (live) {
                    stallSamplePos = -1L
                    continue
                }

                if (pos != stallSamplePos) {
                    stallSamplePos = pos
                    stallSampleAtElapsed = now
                    continue
                }

                val frozenFor = now - stallSampleAtElapsed
                if (stallSamplePos < 0L) continue

                // End-of-track freeze: HTTP streams often sit BUFFERING at EOF
                // and never fire onEnded. Still honor a real pause (wantPlay
                // false and not buffering). Prefer the prepared next on advance.
                if (nearEnd && frozenFor >= NEAR_END_ADVANCE_MS && (wantPlay || buffering)) {
                    Log.i(
                        TAG,
                        "near-end freeze ${frozenFor}ms pos=$pos/$duration " +
                            "playing=$playing buffering=$buffering wantPlay=$wantPlay → onEnded"
                    )
                    onEngineEnded()
                    stallSamplePos = -1L
                    continue
                }

                // Intended to play but producing no audio, stuck off zero.
                // Covers VLC Stopped-without-EndReached mid-file-end.
                if (wantPlay && !playing && !buffering && pos > 1_000L &&
                    frozenFor >= ENDED_SILENCE_MS && remaining <= 5_000L
                ) {
                    Log.i(TAG, "silent near end ${frozenFor}ms pos=$pos/$duration → onEnded")
                    onEngineEnded()
                    stallSamplePos = -1L
                    continue
                }

                if (!wantPlay) {
                    // Media3 audio-focus pause clears playWhenReady; we still
                    // resume unless this was an explicit user pause or a call.
                    val inCall = isInCall()
                    if (userWantsPlay && !playing && !buffering && !inCall &&
                        frozenFor >= UNEXPECTED_PAUSE_MS && !nearEnd
                    ) {
                        Log.i(TAG, "unexpected pause ${frozenFor}ms pos=$pos — resume")
                        acquireSleepLocks()
                        hooks.play()
                    }
                    continue
                }
                // Mid-track buffering is expected on Jellyfin/HTTP.
                if (buffering) continue

                val threshold = if (remote) NETWORK_STALL_MS else STALL_MS

                if (userWantsPlay && !playing && !isInCall() && frozenFor >= UNEXPECTED_PAUSE_MS) {
                    Log.i(TAG, "unexpected pause ${frozenFor}ms pos=$pos — resume")
                    acquireSleepLocks()
                    hooks.play()
                    continue
                }

                if (frozenFor >= threshold) {
                    Log.w(
                        TAG,
                        "stall watchdog: pos frozen at $pos for ${frozenFor}ms remote=$remote — recovering"
                    )
                    recoverFromAudioGlitch(atPositionMs = pos)
                }
            }
        }
    }

    private fun persistState(immediate: Boolean = false) {
        val pending = pendingRemoteRestore
        val snap = queueManager.getSnapshot()
        val pos = if (pending != null) pending.positionMs else getPositionMs()
        val ready = if (pending != null) false else userWantsPlay || engineHooks?.getPlayWhenReady() == true
        persistDebounceJob?.cancel()
        val write = {
            stateStore.save(snap, pos, ready)
        }
        if (immediate) {
            serviceScope.launch(Dispatchers.IO) { write() }
            return
        }
        persistDebounceJob = serviceScope.launch(Dispatchers.IO) {
            delay(750)
            write()
        }
    }

    private fun isInCall(): Boolean {
        return try {
            val mode = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
            false
        }
    }

    private fun acquireSleepLocks() {
        try {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuriplayer:playback")
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock", e)
        }
        if (!isRemoteSong(_nowPlaying.value)) return
        try {
            @Suppress("DEPRECATION")
            val lock = wifiLock ?: (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yuriplayer:wifi")
                .also {
                    it.setReferenceCounted(false)
                    wifiLock = it
                }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "wifiLock", e)
        }
    }

    private fun releaseSleepLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock release", e)
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wifiLock release", e)
        }
    }

    private fun recoverFromAudioGlitch(atPositionMs: Long? = null) {
        if (recoveringAudio) return
        recoveringAudio = true
        serviceScope.launch {
            try {
                val hooks = engineHooks
                val pos = (atPositionMs ?: hooks?.getPositionMs() ?: 0L).coerceAtLeast(0L)
                val wasPlaying = userWantsPlay ||
                    hooks?.getPlayWhenReady() == true ||
                    hooks?.isPlaying() == true
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

    private fun publishViewState() {
        val song = queueManager.currentSong()
        _nowPlaying.value = song
        val engineDur = engineHooks?.getDurationMs()?.takeIf { it > 0L } ?: 0L
        val metaDur = song?.durationMs?.takeIf { it > 0L } ?: 0L
        _viewState.value = PlayerViewState(
            song = song,
            next = queueManager.peekNext(),
            previous = queueManager.peekPrevious(),
            playing = _isPlaying.value,
            positionMs = getPositionMs(),
            durationMs = if (engineDur > 0L) engineDur else metaDur
        )
    }

    private fun updateForegroundNotification() {
        publishViewState()
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
                Log.d(TAG, "MediaStyle skipped: ${e.message}")
            }
        }
        return builder.build()
    }

    fun isPlaying(): Boolean = engineHooks?.isPlaying() == true
    fun getCurrentSong(): Song? = queueManager.currentSong()

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
        stopForAppClosed()
        super.onTaskRemoved(rootIntent)
    }

    /** Recents swipe / close-the-app. Home and lock-screen keep playing. */
    private fun stopForAppClosed() {
        userWantsPlay = false
        val pos = getPositionMs()
        val snap = queueManager.getSnapshot()
        runCatching { engineHooks?.pause() }
        runCatching { engineHooks?.deactivate() }
        releaseSleepLocks()
        try {
            stateStore.save(snap, pos, playWhenReady = false)
        } catch (e: Exception) {
            Log.w(TAG, "persist on close", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        persistState(immediate = true)
        persistDebounceJob?.cancel()
        persistJob?.cancel()
        stallWatchJob?.cancel()
        serviceScope.cancel()
        releaseSleepLocks()
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
        private const val STALL_MS = 4_000L
        private const val NETWORK_STALL_MS = 12_000L
        private const val NEAR_END_MS = 2_500L
        private const val NEAR_END_ADVANCE_MS = 700L
        private const val WARMUP_NEXT_MS = 8_000L
        private const val ENDED_SILENCE_MS = 1_500L
        private const val UNEXPECTED_PAUSE_MS = 1_200L
        private const val ADVANCE_DEBOUNCE_MS = 600L
        private const val AUTO_ADVANCE_IGNORE_MS = 800L
        private const val WATCHDOG_GRACE_MS = 2_000L
        private const val STICKY_SEEK_MS = 1_200L
        private const val USER_SEEK_GUARD_MS = 1_000L
        private const val SEEK_CONFIRM_MS = 600L
        private const val RESTORE_PREPARE_DELAY_MS = 40L
        private const val STARTUP_IGNORE_SESSION_PLAY_MS = 2_500L
    }
}
