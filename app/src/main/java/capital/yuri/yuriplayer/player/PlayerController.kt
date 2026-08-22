package capital.yuri.yuriplayer.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import capital.yuri.yuriplayer.core.log.yuriLog
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioSourcePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerController(
    private val context: Context,
    private val historyStore: PlaybackHistoryStore,
    private val radioEngine: RadioEngine,
    private val queueManager: QueueManager
) {

    private var service: MusicService? = null
    private var bound = false
    private var pendingAction: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceNowPlayingJob: Job? = null
    private var serviceViewJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Song?>(queueManager.currentSong())
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    private val _viewState = MutableStateFlow(PlayerViewState(song = queueManager.currentSong()))
    val viewState: StateFlow<PlayerViewState> = _viewState.asStateFlow()

    val historyEntries: StateFlow<List<HistoryEntry>> get() = historyStore.entries

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicService.LocalBinder ?: return
            service = local.getService()
            bound = true
            _isConnected.value = true
            _nowPlaying.value = service?.getCurrentSong() ?: queueManager.currentSong()
            _viewState.value = service?.viewState?.value ?: PlayerViewState(
                song = queueManager.currentSong(),
                next = queueManager.peekNext(),
                previous = queueManager.peekPrevious()
            )
            serviceNowPlayingJob?.cancel()
            serviceViewJob?.cancel()
            val svc = service
            if (svc != null) {
                serviceNowPlayingJob = scope.launch {
                    svc.nowPlaying.collect { song ->
                        _nowPlaying.value = song ?: queueManager.currentSong()
                    }
                }
                serviceViewJob = scope.launch {
                    svc.viewState.collect { state ->
                        _viewState.value = state
                        _nowPlaying.value = state.song
                    }
                }
            }
            val pending = pendingAction
            pendingAction = null
            try {
                pending?.invoke()
            } catch (e: Exception) {
                log.e(e) { "pending action failed" }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceNowPlayingJob?.cancel()
            serviceNowPlayingJob = null
            serviceViewJob?.cancel()
            serviceViewJob = null
            service = null
            bound = false
            _isConnected.value = false
        }
    }

    init {
        scope.launch {
            queueManager.snapshot.collect { snap ->
                if (serviceViewJob == null) {
                    _nowPlaying.value = snap.currentSong
                    _viewState.value = PlayerViewState(
                        song = snap.currentSong,
                        next = queueManager.peekNext(),
                        previous = queueManager.peekPrevious()
                    )
                }
            }
        }
    }

    fun bind() {
        val intent = Intent(context, MusicService::class.java)
        ContextCompat.startForegroundService(context, intent)
        if (!bound) {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        serviceNowPlayingJob?.cancel()
        serviceNowPlayingJob = null
        serviceViewJob?.cancel()
        serviceViewJob = null
        bound = false
        service = null
        _isConnected.value = false
        pendingAction = null
    }

    private fun runOrQueue(action: (MusicService) -> Unit) {
        ensureServiceStarted()
        val s = service
        if (s != null) {
            action(s)
        } else {
            log.i { "service not bound yet — queueing action" }
            pendingAction = { service?.let(action) }
        }
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        runOrQueue { it.playSource(songs, startIndex, autoPlay = true) }
    }

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        source: ColdSource? = null
    ) {
        runOrQueue { it.playSource(songs, startIndex, autoPlay = true, source = source) }
    }

    private fun launchRadioSession(session: capital.yuri.yuriplayer.player.radio.RadioSession) {
        queueManager.setRepeatMode(RepeatMode.OFF)
        queueManager.setRadioSession(session)
        val batch = radioEngine.planBatch() ?: return
        runOrQueue {
            it.setRepeatMode(RepeatMode.OFF)
            it.playSource(
                songs = batch.songs,
                startIndex = 0,
                autoPlay = true,
                source = batch.source
            )
            queueManager.setRadioSession(session)
            queueManager.setRadioUpcoming(emptyList())
        }
    }

    fun startAlbumRadio(album: AlbumItem) {
        launchRadioSession(radioEngine.startAlbumRadio(album))
    }

    fun startArtistRadio(artistName: String) {
        launchRadioSession(radioEngine.startArtistRadio(artistName))
    }

    fun startPlaylistRadio(songs: List<Song>, playlistName: String?) {
        if (songs.isEmpty()) return
        launchRadioSession(radioEngine.startPlaylistRadio(songs, playlistName))
    }

    fun startSongRadio(song: Song) {
        val name = song.effectiveAlbumArtist ?: song.artist ?: song.displayArtist
        startArtistRadio(name)
    }

    fun stopRadio() {
        radioEngine.stopRadio()
        queueManager.clearRadio()
    }

    /** Apply prefs for the active radio session and replan cold live. */
    fun applyRadioPrefs(prefs: RadioSourcePrefs) {
        runOrQueue { it.applyRadioPrefs(prefs) }
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String) {
        runOrQueue { it.updateColdFromSource(songs, sourceId) }
    }

    fun addToHotQueue(song: Song) {
        runOrQueue { it.addToHotQueue(song) }
    }

    fun addToHotQueue(songs: List<Song>) {
        runOrQueue { it.addToHotQueue(songs) }
    }

    fun clearHotQueue() {
        runOrQueue { it.clearHotQueue() }
    }

    fun removeFromHot(index: Int) {
        service?.removeFromHot(index) ?: queueManager.removeFromQueue(index)
    }

    fun removeFromCold(index: Int) {
        service?.removeFromCold(index) ?: queueManager.removeFromContext(index)
    }

    fun moveHot(from: Int, to: Int) {
        service?.moveHot(from, to) ?: queueManager.moveInQueue(from, to)
    }

    fun moveCold(from: Int, to: Int) {
        service?.moveCold(from, to) ?: queueManager.moveInContext(from, to)
    }

    fun moveColdToHot(index: Int) {
        service?.moveColdToHot(index) ?: queueManager.moveColdToHot(index)
    }

    fun playQueueItem(lane: QueueLane, index: Int) {
        runOrQueue { it.playQueueItem(lane, index) }
    }

    fun setShuffle(enabled: Boolean) {
        runOrQueue { it.setShuffle(enabled) }
    }

    fun toggleShuffle() {
        val enabled = !queueManager.getSnapshot().shuffleEnabled
        setShuffle(enabled)
    }

    fun cycleRepeatMode() {
        runOrQueue { it.cycleRepeatMode() }
    }

    fun setRepeatMode(mode: RepeatMode) {
        runOrQueue { it.setRepeatMode(mode) }
    }

    fun play() {
        runOrQueue { it.play() }
    }

    fun pause() {
        runOrQueue { it.pause() }
    }

    fun togglePlayPause() {
        if (service?.isPlaying() == true) pause()
        else play()
    }

    fun skipToNext() {
        runOrQueue { it.skipToNext() }
    }

    fun skipToPrevious(forceTrackChange: Boolean = false) {
        runOrQueue { it.skipToPrevious(forceTrackChange) }
    }

    fun seekTo(positionMs: Long) {
        runOrQueue { it.seekTo(positionMs) }
    }

    fun seekToFraction(fraction: Float) {
        runOrQueue { it.seekToFraction(fraction) }
    }

    fun peekNext(): Song? = service?.peekNext() ?: queueManager.peekNext()
    fun peekPrevious(): Song? = service?.peekPrevious() ?: queueManager.peekPrevious()

    fun clearHistory() {
        historyStore.clear()
        service?.clearHistory()
    }

    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
        service?.setHistoryMax(n)
    }

    fun isPlayingNow(): Boolean = service?.isPlaying() == true
    fun getCurrentSong(): Song? = service?.getCurrentSong() ?: queueManager.currentSong()
    fun getCurrentIndex(): Int = service?.getCurrentIndex() ?: -1
    fun getPositionMs(): Long = service?.getPositionMs() ?: 0L
    fun getDurationMs(): Long = service?.getDurationMs() ?: 0L
    fun getQueue(): List<Song> = service?.getQueue() ?: emptyList()
    fun getQueueSnapshot(): QueueSnapshot = queueManager.getSnapshot()
    val snapshot: StateFlow<QueueSnapshot> get() = queueManager.snapshot

    fun queueSnapshotFlow(): StateFlow<QueueSnapshot> = queueManager.snapshot

    private fun ensureServiceStarted() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MusicService::class.java)
        )
        if (!bound) {
            context.bindService(
                Intent(context, MusicService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    companion object {
        private val log = yuriLog("Ctrl")
    }
}
