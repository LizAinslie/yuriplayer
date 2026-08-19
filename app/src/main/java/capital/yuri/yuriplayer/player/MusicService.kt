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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.extractStreamHeaders
import capital.yuri.yuriplayer.player.engine.isNetworkUri
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
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

class MusicService : MediaSessionService() {

    private val binder = LocalBinder()
    private val queueManager: QueueManager by inject()
    private val stateStore: PlaybackStateStore by inject()
    private val historyStore: PlaybackHistoryStore by inject()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var stallWatchJob: Job? = null
    private var restoredOnce = false
    private var advancing = false
    private var recoveringAudio = false
    private var lastHistoryKey: String? = null
    private var loopGeneration = 0L

    private var stallSamplePos = -1L
    private var stallSampleAtElapsed = 0L

    private var stickySeekTargetMs: Long = -1L
    private var stickySeekUntilElapsed: Long = 0L
    private var userSeekGuardUntilElapsed: Long = 0L

    /**
     * Remote (Jellyfin/Subsonic) restore is deferred so cold start never blocks on
     * network prepare. Metadata + notification still update immediately.
     */
    private var pendingRemoteRestore: PendingRemoteRestore? = null

    private data class PendingRemoteRestore(
        val positionMs: Long,
        val wasPlayWhenReady: Boolean
    )

    /** Shared HTTP factory so Jellyfin/Subsonic tokens apply to stream requests. */
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(25_000)
        .setUserAgent("YuriPlayer/0.1")

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

        // Leaner buffers so first remote (Jellyfin) prepare returns sooner on mid-range devices
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 40_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // NETWORK so remote streams keep Wi‑Fi / radio awake
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun isRepeatOne(): Boolean =
        queueManager.getSnapshot().repeatMode == RepeatMode.ONE

    private fun inUserSeekGuard(): Boolean =
        SystemClock.elapsedRealtime() < userSeekGuardUntilElapsed

    private fun songUri(song: Song): Uri = MusicServicePlaybackHooks.songUri(song)

    private fun isRemoteSong(song: Song?): Boolean {
        if (song == null) return false
        if (isVirtualLibraryPath(song.path)) return true
        return isNetworkUri(songUri(song))
    }

    private fun mediaItemUriAt(index: Int): Uri? {
        val p = player ?: return null
        if (index < 0 || index >= p.mediaItemCount) return null
        return p.getMediaItemAt(index).localConfiguration?.uri
    }

    private fun urisEqual(a: Uri?, b: Uri?): Boolean = MusicServicePlaybackHooks.urisEqual(a, b)

    private fun nextLoopItem(song: Song): MediaItem {
        loopGeneration++
        return toMediaItem(song, mediaIdSuffix = "loop-$loopGeneration")
    }

    private fun clearStickySeek() {
        stickySeekTargetMs = -1L
        stickySeekUntilElapsed = 0L
    }

    /** Apply Jellyfin/Subsonic auth headers before any network prepare. */
    private fun applyStreamHeaders(song: Song?) {
        if (song == null) return
        val headers = extractStreamHeaders(song)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
    }

    /**
     * If a remote track was restored without preparing, do that now (on play).
     * Returns true when a deferred prepare was kicked off.
     */
    private fun flushPendingRemoteRestore(autoPlay: Boolean): Boolean {
        val pending = pendingRemoteRestore ?: return false
        pendingRemoteRestore = null
        Log.i(TAG, "flushPendingRemoteRestore pos=${pending.positionMs} autoPlay=$autoPlay")
        rebufferWindow(pending.positionMs, autoPlay = autoPlay, forceReload = true)
        return true
    }

    private fun hardRestartCurrent(autoPlay: Boolean = true, startPositionMs: Long = 0L) {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        val wasPlaying = autoPlay || p.playWhenReady
        val pos = startPositionMs.coerceAtLeast(0L)
        clearStickySeek()
        pendingRemoteRestore = null
        Log.i(TAG, "hardRestartCurrent '${current.displayTitle}' pos=$pos gen=$loopGeneration")
        try {
            applyStreamHeaders(current)
            p.playWhenReady = false
            p.stop()
            p.clearMediaItems()
            p.setMediaItem(nextLoopItem(current), /* resetPosition = */ true)
            p.prepare()
            if (pos > 0L) p.seekTo(pos)
            p.repeatMode = Player.REPEAT_MODE_OFF
            if (wasPlaying) {
                p.playWhenReady = true
                p.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "hardRestartCurrent failed", e)
            rebufferWindow(pos, autoPlay = wasPlaying, forceReload = true)
            return
        }
        _nowPlaying.value = current
        updateForegroundNotification()
        persistState()
    }

    private fun softNormalizeWindow(): Boolean {
        val p = player ?: return false
        val current = queueManager.currentSong() ?: return false
        if (isRepeatOne()) return true

        val playingUri = mediaItemUriAt(p.currentMediaItemIndex)
        val wantUri = songUri(current)
        if (!urisEqual(playingUri, wantUri)) {
            Log.w(
                TAG,
                "softNormalize refused desync: playing=$playingUri " +
                    "want='${current.displayTitle}'"
            )
            return false
        }

        try {
            while (p.currentMediaItemIndex > 0) p.removeMediaItem(0)
            while (p.mediaItemCount > 1) p.removeMediaItem(p.mediaItemCount - 1)
            queueManager.peekNext()?.let { p.addMediaItem(toMediaItem(it)) }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "softNormalizeWindow failed", e)
            return false
        }
    }

    private fun ensurePlayerMatchesQueue(autoPlay: Boolean = true) {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        if (pendingRemoteRestore != null && isRemoteSong(current)) {
            flushPendingRemoteRestore(autoPlay = autoPlay)
            return
        }
        val playingUri = mediaItemUriAt(p.currentMediaItemIndex)
        if (urisEqual(playingUri, songUri(current))) {
            if (!softNormalizeWindow()) {
                rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
            } else {
                _nowPlaying.value = current
                updateForegroundNotification()
            }
            return
        }
        Log.w(
            TAG,
            "ensurePlayerMatchesQueue desync → rebuffer '${current.displayTitle}'"
        )
        rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
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
            pendingRemoteRestore = null
            updateForegroundNotification()
            return
        }

        clearStickySeek()
        pendingRemoteRestore = null
        applyStreamHeaders(current)

        val repeatOne = isRepeatOne()
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
            if (startPositionMs > 0L && abs(p.currentPosition - startPositionMs) > 400L) {
                p.seekTo(0, startPositionMs)
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
                "startMs=$startPositionMs autoPlay=$autoPlay force=$forceReload remote=${isRemoteSong(current)}"
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

    private fun updateNextMediaItemOnly() {
        val p = player ?: return
        val current = queueManager.currentSong() ?: return
        if (pendingRemoteRestore != null) {
            // Window not prepared yet — next item will be attached on flush.
            return
        }
        val playingUri = mediaItemUriAt(p.currentMediaItemIndex)
        if (!urisEqual(playingUri, songUri(current))) {
            rebufferWindow(
                startPositionMs = p.currentPosition.coerceAtLeast(0L),
                autoPlay = p.playWhenReady,
                forceReload = true
            )
            return
        }
        while (p.mediaItemCount > p.currentMediaItemIndex + 1) {
            p.removeMediaItem(p.mediaItemCount - 1)
        }
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
        clearStickySeek()
        pendingRemoteRestore = null
        val p = player
        when {
            result.finished -> {
                p?.pause()
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
                    if (!softNormalizeWindow()) {
                        rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
                    } else {
                        persistState()
                    }
                } else {
                    Log.i(TAG, "fallback rebuffer advance → '${target.displayTitle}'")
                    rebufferWindow(0L, autoPlay = autoPlay, forceReload = true)
                }
            }
        }
    }

    private fun syncQueueAfterExoAutoAdvance() {
        if (advancing) return
        if (inUserSeekGuard()) {
            Log.i(TAG, "AUTO transition ignored — inside user seek guard")
            ensurePlayerMatchesQueue(autoPlay = player?.playWhenReady == true)
            return
        }
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
                    ensurePlayerMatchesQueue(autoPlay = true)
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
        val p = player
        if (p != null && !p.isPlaying && pendingRemoteRestore != null) {
            play()
            return
        }
        p?.let { if (it.isPlaying) it.pause() else it.play() }
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
                currentPositionMs = player?.currentPosition ?: 0L,
                forceTrackChange = forceTrackChange
            )
        )
    }

    fun seekTo(positionMs: Long) {
        if (pendingRemoteRestore != null) {
            // Seek before first prepare — just update the pending position.
            val pending = pendingRemoteRestore!!
            pendingRemoteRestore = pending.copy(positionMs = positionMs.coerceAtLeast(0L))
            persistState()
            return
        }
        val p = player ?: return
        if (p.mediaItemCount <= 0) return

        val playerDuration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
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

        val current = queueManager.currentSong()
        val idx = when {
            current != null && urisEqual(mediaItemUriAt(0), songUri(current)) -> 0
            current != null -> {
                var found = p.currentMediaItemIndex.coerceAtLeast(0)
                for (i in 0 until p.mediaItemCount) {
                    if (urisEqual(mediaItemUriAt(i), songUri(current))) {
                        found = i
                        break
                    }
                }
                found
            }
            else -> p.currentMediaItemIndex.coerceAtLeast(0)
        }

        val now = SystemClock.elapsedRealtime()
        stickySeekTargetMs = target
        stickySeekUntilElapsed = now + STICKY_SEEK_MS
        userSeekGuardUntilElapsed = now + USER_SEEK_GUARD_MS
        stallSamplePos = target
        stallSampleAtElapsed = now

        try {
            p.seekTo(idx, target)
            if (p.playWhenReady && !p.isPlaying && p.playbackState == Player.STATE_READY) {
                p.play()
            }
            Log.i(TAG, "seekTo target=$target (raw=$positionMs) duration=$duration idx=$idx")
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed", e)
            clearStickySeek()
        }
        persistState()
    }

    fun seekToFraction(fraction: Float) {
        val p = player
        val metaDuration = queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
        val playerDuration = p?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
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
    fun getHistory(): List<HistoryEntry> = historyStore.entries.value
    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
    }

    private fun toMediaItem(song: Song, mediaIdSuffix: String? = null): MediaItem =
        MusicServicePlaybackHooks.toMediaItem(song, mediaIdSuffix)

    /**
     * Restore queue metadata immediately so the UI can bind.
     * Local files: short yield then prepare.
     * Remote (Jellyfin): **do not prepare** until the user hits play — network
     * handshake was blocking first frames on mid-range devices.
     */
    private fun restorePlaybackState() {
        if (restoredOnce) return
        restoredOnce = true
        serviceScope.launch {
            val saved = withContext(Dispatchers.IO) { stateStore.load() } ?: return@launch
            queueManager.restore(saved.snapshot)
            val current = queueManager.currentSong()
            _nowPlaying.value = current
            updateForegroundNotification()
            // Let Activity/Compose paint before any player work
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
                // Intentionally no rebufferWindow here — play() flushes it.
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
                val p = player ?: continue
                if (recoveringAudio || advancing || inUserSeekGuard()) {
                    stallSamplePos = -1L
                    continue
                }
                if (!p.playWhenReady) {
                    stallSamplePos = -1L
                    continue
                }
                if (p.playbackState != Player.STATE_READY) {
                    stallSamplePos = p.currentPosition
                    stallSampleAtElapsed = SystemClock.elapsedRealtime()
                    continue
                }

                val pos = p.currentPosition.coerceAtLeast(0L)
                val duration = p.duration.takeIf { it > 0 } ?: 0L
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
                    (p.isPlaying || p.playWhenReady)
                if (looksStuck && stallSamplePos >= 0L) {
                    Log.w(
                        TAG,
                        "stall watchdog: pos frozen at $pos for ${frozenFor}ms " +
                            "isPlaying=${p.isPlaying} — recovering"
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
            playWhenReady = if (pending != null) false else player?.playWhenReady == true
        )
    }

    private fun recoverFromAudioGlitch(atPositionMs: Long? = null) {
        if (recoveringAudio) return
        recoveringAudio = true
        serviceScope.launch {
            try {
                val p = player
                val pos = (atPositionMs ?: p?.currentPosition ?: 0L).coerceAtLeast(0L)
                val wasPlaying = p?.playWhenReady == true || p?.isPlaying == true
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
            if (playbackState != Player.STATE_ENDED || advancing) return

            if (inUserSeekGuard()) {
                val p = player ?: return
                val target = stickySeekTargetMs.takeIf { it >= 0L } ?: p.currentPosition
                Log.i(TAG, "STATE_ENDED suppressed after user seek → reseek $target")
                try {
                    val idx = p.currentMediaItemIndex.coerceAtLeast(0)
                    p.seekTo(idx, target.coerceAtLeast(0L))
                    if (p.playWhenReady) p.play()
                } catch (e: Exception) {
                    Log.w(TAG, "reseek after suppressed ENDED failed", e)
                    rebufferWindow(
                        startPositionMs = target.coerceAtLeast(0L),
                        autoPlay = p.playWhenReady,
                        forceReload = true
                    )
                }
                return
            }

            val p = player
            if (p != null && p.hasNextMediaItem()) {
                Log.i(TAG, "STATE_ENDED with next item — defer to AUTO transition")
                return
            }

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

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error code=${error.errorCode} ${error.message}", error)
            val cause = error.cause
            val shouldRecover =
                cause is AudioSink.UnexpectedDiscontinuityException ||
                    cause is IllegalArgumentException ||
                    error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            if (shouldRecover) {
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

    fun getPositionMs(): Long {
        pendingRemoteRestore?.let { return it.positionMs }
        val real = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
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
        val p = player ?: return 0L
        val d = p.duration
        if (d > 0L && d != C.TIME_UNSET) return d
        return queueManager.currentSong()?.durationMs?.takeIf { it > 0 } ?: 0L
    }

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
        stallWatchJob?.cancel()
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
        private const val STALL_POLL_MS = 500L
        private const val STALL_MS = 2_000L
        private const val NEAR_END_MS = 500L
        private const val STICKY_SEEK_MS = 1_200L
        private const val USER_SEEK_GUARD_MS = 1_000L
        private const val SEEK_CONFIRM_MS = 600L
        /** Delay after local queue restore before Exo prepare — keeps first UI frames free. */
        private const val RESTORE_PREPARE_DELAY_MS = 40L
    }
}
