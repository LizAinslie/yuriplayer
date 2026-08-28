package capital.yuri.yuriplayer.desktop.player

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.ColdSourceType
import capital.yuri.yuriplayer.core.player.PlaybackEngine
import capital.yuri.yuriplayer.core.player.PlaybackSnapshot
import capital.yuri.yuriplayer.core.player.QueueLane
import capital.yuri.yuriplayer.core.player.RepeatMode
import capital.yuri.yuriplayer.core.player.toPlaybackMedia
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.QueueManager
import capital.yuri.yuriplayer.player.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Desktop playback host: the shared core [QueueManager] (Song-based, same one
 * Android drives) + a platform [PlaybackEngine], exposed directly on [Song].
 */
class DesktopPlayerHost(
    private val queue: QueueManager,
    private val engine: PlaybackEngine
) {
    private val log = yuriLog("Player")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Refresh network stream URLs (Jellyfin/Subsonic tokens) before playback. */
    var mediaFor: (Song) -> Song = { it }

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current

    private val _history = MutableStateFlow<List<Song>>(emptyList())
    val history: StateFlow<List<Song>> = _history

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    val isPlaying: StateFlow<Boolean> = engine.isPlaying

    val hotQueue: StateFlow<List<Song>> =
        queue.snapshot.map { it.hotQueue }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val coldQueue: StateFlow<List<Song>> =
        queue.snapshot.map { it.coldQueue }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val coldSource: StateFlow<ColdSource?> =
        queue.snapshot.map { it.coldSource }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val shuffle: StateFlow<Boolean> =
        queue.snapshot.map { it.shuffleEnabled }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val repeat: StateFlow<RepeatMode> =
        queue.snapshot.map { it.repeatMode }
            .stateIn(scope, SharingStarted.Eagerly, RepeatMode.OFF)

    private val listener = object : PlaybackEngine.Listener {
        override fun onEnded() {
            advanceAndApply(fromUser = false)
        }

        override fun onAutoAdvanced() {
            advanceAndApply(fromUser = false)
        }

        override fun onError(message: String, recoverable: Boolean) {
            log.e { "engine error: $message recoverable=$recoverable" }
        }
    }

    init {
        engine.addListener(listener)
    }

    // ── transport ──────────────────────────────────────────────────────────

    fun play(tracks: List<Song>, startIndex: Int = 0, source: ColdSource? = null) {
        if (tracks.isEmpty()) return
        queue.playSource(tracks, startIndex, source)
        rebuffer(autoPlay = true)
    }

    fun togglePlay() {
        if (_current.value == null) {
            rebuffer(autoPlay = true)
        } else if (engine.isPlaying.value) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    fun pause() = engine.pause()
    fun stop() = engine.stop()
    fun next() = advanceAndApply(fromUser = true)
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0L))

    fun previous() {
        applyResult(queue.skipPrevious(engine.getPositionMs(), forceTrackChange = false))
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        engine.setVolume((v * 100).toInt())
    }

    fun positionMs(): Long = engine.getPositionMs()
    fun durationMs(): Long = engine.getDurationMs()

    // ── queue mutations ─────────────────────────────────────────────────────

    fun toggleShuffle() = queue.setShuffle(!queue.getSnapshot().shuffleEnabled)
    fun setShuffle(enabled: Boolean) = queue.setShuffle(enabled)
    fun cycleRepeat() = queue.cycleRepeatMode()
    fun setRepeat(mode: RepeatMode) = queue.setRepeatMode(mode)

    fun enqueue(track: Song) = queue.addToQueue(track)
    fun enqueueAll(tracks: List<Song>) = queue.addToQueue(tracks)
    fun clearHot() = queue.clearHotQueue()
    fun clearHistory() {
        _history.value = emptyList()
    }
    fun moveHot(from: Int, to: Int) = queue.moveInQueue(from, to)
    fun moveCold(from: Int, to: Int) = queue.moveInContext(from, to)

    fun playTrack(track: Song, context: List<Song> = emptyList()) {
        val snap = queue.getSnapshot()
        val hotIdx = snap.hotQueue.indexOfFirst { it.songKey == track.songKey }
        if (hotIdx >= 0) {
            queue.playItem(QueueLane.HOT, hotIdx)
            rebuffer(autoPlay = true)
            return
        }
        val coldIdx = snap.coldQueue.indexOfFirst { it.songKey == track.songKey }
        if (coldIdx >= 0) {
            queue.playItem(QueueLane.COLD, coldIdx)
            rebuffer(autoPlay = true)
            return
        }
        play(if (context.isNotEmpty()) context else listOf(track),
            context.indexOfFirst { it.songKey == track.songKey }.coerceAtLeast(0))
    }

    // ── source checks ───────────────────────────────────────────────────────

    fun isPlayingFromAlbum(albumKey: String): Boolean =
        queue.getSnapshot().isPlayingFromAlbum(albumKey)

    fun isPlayingFromPlaylist(playlistId: String): Boolean =
        queue.getSnapshot().isPlayingFromPlaylist(playlistId)

    fun isPlayingFromArtist(artistName: String): Boolean =
        queue.coldSource()?.matches(ColdSourceType.ARTIST, artistName) == true

    // ── persistence ─────────────────────────────────────────────────────────

    fun snapshot(): PlaybackSnapshot {
        val s = queue.getSnapshot()
        return PlaybackSnapshot(
            queue = s.flatQueue,
            linear = s.coldOriginal,
            index = 0,
            history = _history.value,
            shuffle = s.shuffleEnabled,
            repeat = s.repeatMode,
            volume = _volume.value,
            positionMs = engine.getPositionMs(),
            hotQueue = s.hotQueue,
            coldQueue = s.coldQueue,
            coldOriginal = s.coldOriginal,
            coldSource = s.coldSource,
            lane = s.lane,
            indexInLane = s.indexInLane
        )
    }

    fun restore(snap: PlaybackSnapshot, play: Boolean = false) {
        _volume.value = snap.volume.coerceIn(0f, 1f)
        engine.setVolume((_volume.value * 100).toInt())
        _history.value = snap.history
        val qs = QueueSnapshot(
            hotQueue = snap.hotQueue,
            coldQueue = snap.coldQueue,
            coldOriginal = snap.coldOriginal,
            coldSource = snap.coldSource,
            lane = snap.lane,
            indexInLane = snap.indexInLane,
            shuffleEnabled = snap.shuffle,
            repeatMode = snap.repeat,
            playedStack = snap.history.reversed()
        )
        queue.restore(qs)
        rebuffer(snap.positionMs.coerceAtLeast(0L), play)
    }

    fun release() {
        engine.removeListener(listener)
        engine.release()
        scope.cancel()
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun rebuffer(startMs: Long = 0L, autoPlay: Boolean = false) {
        val song = queue.currentSong()
        if (song == null) {
            engine.stop()
            _current.value = null
            return
        }
        val track = mediaFor(song)
        recordHistory(track)
        _current.value = track
        val next = queue.peekNext()?.let { mediaFor(it) }
        log.i { "load '${track.songKey}' play=$autoPlay startMs=$startMs" }
        engine.load(track.toPlaybackMedia(), next?.toPlaybackMedia(), startMs)
        if (autoPlay) engine.play() else engine.pause()
    }

    private fun advanceAndApply(fromUser: Boolean) {
        applyResult(queue.advance(fromUser))
    }

    private fun applyResult(result: QueueManager.AdvanceResult) {
        when {
            result.finished -> {
                engine.pause()
                _current.value = null
            }
            result.seekToStart -> engine.seekTo(0L)
            result.reload -> rebuffer(autoPlay = true)
            result.song != null -> rebuffer(autoPlay = true)
        }
    }

    private fun recordHistory(track: Song) {
        val next = listOf(track) + _history.value.filterNot { it.songKey == track.songKey }
        _history.value = next.take(HISTORY_CAP)
    }

    companion object {
        private const val HISTORY_CAP = 50
    }
}
