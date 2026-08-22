package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QueueLane { HOT, COLD }

/**
 * Host-side queue + now-playing. Engines only produce sound.
 *
 * Hot = user "add to queue" (plays next, before the rest of the album).
 * Cold = album / playlist / search context currently playing through.
 */
class PlayerSession(
    private val engine: PlaybackEngine
) {
    /**
     * Refresh network stream URLs (Navidrome/Jellyfin tokens) right before
     * the engine opens them. Identity stays on the original [Track].
     */
    var mediaFor: (Track) -> Track = { it }
    private val _hot = MutableStateFlow<List<Track>>(emptyList())
    val hotQueue: StateFlow<List<Track>> = _hot.asStateFlow()

    private val _cold = MutableStateFlow<List<Track>>(emptyList())
    val coldQueue: StateFlow<List<Track>> = _cold.asStateFlow()

    private var coldOriginal: List<Track> = emptyList()
    private var lane: QueueLane = QueueLane.COLD
    private var indexInLane: Int = -1

    /** Flattened [current] + remaining hot + remaining cold. Snapshot compatibility. */
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _history = MutableStateFlow<List<Track>>(emptyList())
    val history: StateFlow<List<Track>> = _history.asStateFlow()

    val isPlaying: StateFlow<Boolean> = engine.isPlaying

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val listener = object : PlaybackEngine.Listener {
        override fun onEnded() {
            when (_repeat.value) {
                RepeatMode.ONE -> {
                    engine.seekTo(0)
                    engine.play()
                }
                RepeatMode.ALL, RepeatMode.OFF -> advance(fromUser = false)
            }
        }

        override fun onAutoAdvanced() {
            advance(fromUser = false, engineAlreadyMoved = true)
        }
    }

    init {
        engine.addListener(listener)
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        coldOriginal = tracks
        val start = startIndex.coerceIn(tracks.indices)
        val ordered = if (_shuffle.value) {
            val tapped = tracks[start]
            listOf(tapped) + tracks.filter { it.id != tapped.id }.shuffled()
        } else {
            tracks
        }
        _cold.value = ordered
        lane = QueueLane.COLD
        indexInLane = if (_shuffle.value) 0 else start
        publish()
        loadCurrent(0L, play = true)
    }

    fun togglePlay() {
        if (_current.value == null) {
            if (activeList().isNotEmpty()) loadCurrent(0L, play = true)
            return
        }
        if (engine.isPlaying.value) engine.pause() else engine.play()
    }

    fun pause() = engine.pause()
    fun stop() = engine.stop()
    fun next() = advance(fromUser = true)

    fun previous() {
        if (engine.getPositionMs() > RESTART_WINDOW_MS) {
            engine.seekTo(0)
            return
        }
        val prev = when {
            lane == QueueLane.COLD && indexInLane > 0 -> {
                indexInLane--
                lane = QueueLane.COLD
                _cold.value.getOrNull(indexInLane)
            }
            lane == QueueLane.HOT && indexInLane > 0 -> {
                indexInLane--
                lane = QueueLane.HOT
                _hot.value.getOrNull(indexInLane)
            }
            _history.value.isNotEmpty() -> _history.value.first()
            else -> null
        }
        if (prev == null) {
            engine.seekTo(0)
            return
        }
        publish()
        loadCurrent(0L, play = true)
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))

    fun toggleShuffle() {
        val turningOn = !_shuffle.value
        _shuffle.value = turningOn
        val cur = currentTrack()
        if (turningOn) {
            val pool = coldOriginal.ifEmpty { _cold.value }
            val rest = pool.filter { it.id != cur?.id }.shuffled()
            _cold.value = if (cur != null && lane == QueueLane.COLD) listOf(cur) + rest else rest
            if (lane == QueueLane.COLD) indexInLane = 0
        } else {
            val restored = coldOriginal.ifEmpty { _cold.value }
            _cold.value = restored
            if (lane == QueueLane.COLD && cur != null) {
                indexInLane = restored.indexOfFirst { it.id == cur.id }.takeIf { it >= 0 } ?: 0
            }
        }
        publish()
        warmSuccessor()
    }

    fun cycleRepeat() {
        _repeat.value = _repeat.value.next()
        warmSuccessor()
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        engine.setVolume((v * 100).toInt())
    }

    fun skipTo(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        playTrack(q[index], q)
    }

    fun playTrack(track: Track, context: List<Track> = _queue.value.ifEmpty { listOf(track) }) {
        val hotIdx = _hot.value.indexOfFirst { it.id == track.id }
        if (hotIdx >= 0) {
            playHot(hotIdx)
            return
        }
        val coldIdx = _cold.value.indexOfFirst { it.id == track.id }
        if (coldIdx >= 0) {
            playCold(coldIdx)
            return
        }
        play(if (context.isNotEmpty()) context else listOf(track), context.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    fun playHot(index: Int) {
        if (index !in _hot.value.indices) return
        recordHistory(currentTrack())
        val kept = _hot.value.drop(index)
        _hot.value = kept
        lane = QueueLane.HOT
        indexInLane = 0
        publish()
        loadCurrent(0L, play = true)
    }

    fun playCold(index: Int) {
        if (index !in _cold.value.indices) return
        recordHistory(currentTrack())
        lane = QueueLane.COLD
        indexInLane = index
        publish()
        loadCurrent(0L, play = true)
    }

    /** Play next: insert at the head of the hot lane. */
    fun addNext(track: Track) {
        if (nothingLoaded()) {
            play(listOf(track), 0)
            return
        }
        _hot.value = listOf(track) + _hot.value
        publish()
        warmSuccessor()
    }

    /** Append to the hot lane (mobile addToQueue). Never starts playback. */
    fun enqueue(track: Track) {
        if (nothingLoaded()) {
            play(listOf(track), 0)
            return
        }
        _hot.value = _hot.value + track
        publish()
        warmSuccessor()
    }

    fun enqueueAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (nothingLoaded()) {
            play(tracks, 0)
            return
        }
        _hot.value = _hot.value + tracks
        publish()
        warmSuccessor()
    }

    fun moveHot(from: Int, to: Int) = moveLane(_hot, from, to, QueueLane.HOT)
    fun moveCold(from: Int, to: Int) = moveLane(_cold, from, to, QueueLane.COLD)

    fun moveQueueItem(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        // Best-effort: treat as cold-only legacy move.
        if (_hot.value.isEmpty()) {
            _cold.value = q
            coldOriginal = q
            val cur = _current.value
            indexInLane = cur?.let { c -> q.indexOfFirst { it.id == c.id } }?.takeIf { it >= 0 } ?: 0
            publish()
            warmSuccessor()
        }
    }

    fun clearHot() {
        if (lane == QueueLane.HOT) {
            val keep = currentTrack()
            _hot.value = if (keep != null) listOf(keep) else emptyList()
            indexInLane = if (keep != null) 0 else -1
        } else {
            _hot.value = emptyList()
        }
        publish()
        warmSuccessor()
    }

    fun clearQueueKeepCurrent() = clearHot()

    fun clearHistory() {
        _history.value = emptyList()
    }

    fun positionMs(): Long = engine.getPositionMs()
    fun durationMs(): Long = engine.getDurationMs()

    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        queue = _queue.value,
        linear = coldOriginal,
        index = _index.value,
        history = _history.value,
        shuffle = _shuffle.value,
        repeat = _repeat.value,
        volume = _volume.value,
        positionMs = engine.getPositionMs(),
        hotQueue = _hot.value,
        coldQueue = _cold.value,
        coldOriginal = coldOriginal,
        lane = lane,
        indexInLane = indexInLane
    )

    fun restore(snap: PlaybackSnapshot, play: Boolean = false) {
        _shuffle.value = snap.shuffle
        _repeat.value = snap.repeat
        setVolume(snap.volume)
        _history.value = snap.history
        if (snap.coldQueue.isNotEmpty() || snap.hotQueue.isNotEmpty()) {
            _hot.value = snap.hotQueue
            _cold.value = snap.coldQueue
            coldOriginal = snap.coldOriginal.ifEmpty { snap.linear.ifEmpty { snap.coldQueue } }
            lane = snap.lane
            indexInLane = snap.indexInLane
        } else if (snap.queue.isNotEmpty()) {
            _hot.value = emptyList()
            _cold.value = snap.queue
            coldOriginal = snap.linear.ifEmpty { snap.queue }
            lane = QueueLane.COLD
            indexInLane = snap.index.coerceIn(snap.queue.indices)
        } else {
            return
        }
        publish()
        loadCurrent(snap.positionMs.coerceAtLeast(0L), play = play)
        if (!play) engine.pause()
    }

    fun release() {
        engine.removeListener(listener)
        engine.release()
    }

    private fun nothingLoaded(): Boolean =
        currentTrack() == null && _hot.value.isEmpty() && _cold.value.isEmpty()

    private fun currentTrack(): Track? = when (lane) {
        QueueLane.HOT -> _hot.value.getOrNull(indexInLane)
        QueueLane.COLD -> _cold.value.getOrNull(indexInLane)
    }

    private fun activeList(): List<Track> = when (lane) {
        QueueLane.HOT -> _hot.value
        QueueLane.COLD -> _cold.value
    }

    private fun upcomingHot(): List<Track> {
        val hot = _hot.value
        return if (lane == QueueLane.HOT) hot.drop(indexInLane + 1) else hot
    }

    private fun upcomingCold(): List<Track> {
        val cold = _cold.value
        return if (lane == QueueLane.COLD) cold.drop(indexInLane + 1) else cold
    }

    private fun peekNext(): Pair<QueueLane, Int>? {
        val hotUp = upcomingHot()
        if (hotUp.isNotEmpty()) {
            val idx = if (lane == QueueLane.HOT) indexInLane + 1 else 0
            return QueueLane.HOT to idx
        }
        val coldUp = upcomingCold()
        if (coldUp.isNotEmpty()) {
            val idx = if (lane == QueueLane.COLD) indexInLane + 1 else 0
            return QueueLane.COLD to idx
        }
        if (_repeat.value == RepeatMode.ALL && coldOriginal.isNotEmpty()) {
            return QueueLane.COLD to 0
        }
        return null
    }

    private fun peekNextTrack(): Track? {
        upcomingHot().firstOrNull()?.let { return it }
        upcomingCold().firstOrNull()?.let { return it }
        if (_repeat.value == RepeatMode.ALL) return coldOriginal.firstOrNull()
        return null
    }

    private fun consumeCurrent() {
        when (lane) {
            QueueLane.HOT -> {
                val list = _hot.value.toMutableList()
                if (indexInLane in list.indices) list.removeAt(indexInLane)
                _hot.value = list
            }
            QueueLane.COLD -> {
                val list = _cold.value.toMutableList()
                if (indexInLane in list.indices) list.removeAt(indexInLane)
                _cold.value = list
            }
        }
    }

    private fun locate(track: Track): Pair<QueueLane, Int>? {
        _hot.value.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }?.let {
            return QueueLane.HOT to it
        }
        _cold.value.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }?.let {
            return QueueLane.COLD to it
        }
        return null
    }

    private var advancing = false

    private fun advance(fromUser: Boolean, engineAlreadyMoved: Boolean = false) {
        if (advancing) return
        advancing = true
        try {
            advanceBody(fromUser, engineAlreadyMoved)
        } finally {
            advancing = false
        }
    }

    private fun advanceBody(fromUser: Boolean, engineAlreadyMoved: Boolean) {
        val nextTrack = peekNextTrack()
        if (nextTrack == null && fromUser && _repeat.value != RepeatMode.ALL) {
            return
        }
        recordHistory(currentTrack())
        consumeCurrent()
        var target = nextTrack?.let { locate(it) }
        if (target == null && _repeat.value == RepeatMode.ALL && coldOriginal.isNotEmpty()) {
            _cold.value = if (_shuffle.value) coldOriginal.shuffled() else coldOriginal
            target = _cold.value.firstOrNull()?.let { QueueLane.COLD to 0 }
        }
        if (target == null) {
            indexInLane = -1
            publish()
            if (!fromUser) engine.pause()
            return
        }
        lane = target.first
        indexInLane = target.second
        publish()
        if (engineAlreadyMoved) {
            warmSuccessor()
            return
        }
        if (engine.hasPreparedNext() && engine.playPreparedNext()) {
            warmSuccessor()
            return
        }
        loadCurrent(0L, play = true)
    }

    private fun publish() {
        val cur = currentTrack()
        _current.value = cur
        val flat = buildList {
            if (cur != null) add(cur)
            addAll(upcomingHot())
            addAll(upcomingCold())
        }
        _queue.value = flat
        _index.value = 0
    }

    private fun loadCurrent(startMs: Long, play: Boolean) {
        val track = currentTrack() ?: return
        _current.value = track
        val playable = mediaFor(track)
        val next = peekNextTrack()?.takeIf { it.id != track.id }?.let { mediaFor(it) }
        engine.load(playable.toPlaybackMedia(), next?.toPlaybackMedia(), startMs)
        if (play) engine.play() else engine.pause()
        warmSuccessor()
    }

    private fun recordHistory(track: Track?) {
        if (track == null) return
        val next = listOf(track) + _history.value.filterNot { it.id == track.id }
        _history.value = next.take(HISTORY_CAP)
    }

    private fun warmSuccessor() {
        val next = peekNextTrack()
        if (next == null || next.id == currentTrack()?.id) {
            engine.setNext(null)
            return
        }
        engine.setNext(mediaFor(next).toPlaybackMedia())
        engine.warmupNext()
    }

    private fun moveLane(
        flow: MutableStateFlow<List<Track>>,
        from: Int,
        to: Int,
        which: QueueLane
    ) {
        val list = flow.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        flow.value = list
        if (lane == which) {
            indexInLane = remapIndex(indexInLane, from, to)
        }
        publish()
        warmSuccessor()
    }

    private fun remapIndex(current: Int, from: Int, to: Int): Int = when {
        current == from -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    companion object {
        const val RESTART_WINDOW_MS = 3_000L
        private const val HISTORY_CAP = 50
    }
}

data class PlaybackSnapshot(
    val queue: List<Track> = emptyList(),
    val linear: List<Track> = emptyList(),
    val index: Int = 0,
    val history: List<Track> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 1f,
    val positionMs: Long = 0L,
    val hotQueue: List<Track> = emptyList(),
    val coldQueue: List<Track> = emptyList(),
    val coldOriginal: List<Track> = emptyList(),
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = 0
)
