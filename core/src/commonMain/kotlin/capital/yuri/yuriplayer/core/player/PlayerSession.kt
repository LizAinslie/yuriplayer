package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-side queue + now-playing. Engines only produce sound.
 * Shuffle / repeat live here so every backend behaves the same.
 * Previous within [RESTART_WINDOW_MS] seeks to 0 (Spotify 3s rule).
 */
class PlayerSession(
    private val engine: PlaybackEngine
) {
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    /** Unshuffled insertion order. Playing [_queue] is a view of this. */
    private var linear: List<Track> = emptyList()

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
            val target = peekNextIndex(fromUser = false) ?: return
            recordHistory(_current.value)
            applyIndex(target)
            warmSuccessor()
        }
    }

    init {
        engine.addListener(listener)
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        linear = tracks
        val start = startIndex.coerceIn(tracks.indices)
        if (_shuffle.value) {
            applyShuffledView(current = tracks[start])
            loadAt(0, 0L, play = true)
        } else {
            _queue.value = tracks
            loadAt(start, 0L, play = true)
        }
    }

    fun togglePlay() {
        if (_current.value == null) {
            val q = _queue.value
            if (q.isNotEmpty()) loadAt(_index.value.coerceIn(q.indices), 0L, play = true)
            return
        }
        if (engine.isPlaying.value) engine.pause() else engine.play()
    }

    fun pause() = engine.pause()

    fun stop() = engine.stop()

    fun next() = advance(fromUser = true)

    fun previous() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (engine.getPositionMs() > RESTART_WINDOW_MS) {
            engine.seekTo(0)
            return
        }
        val i = _index.value - 1
        when {
            i in q.indices -> loadAt(i, 0L, play = true)
            _repeat.value == RepeatMode.ALL -> loadAt(q.lastIndex, 0L, play = true)
            else -> engine.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))

    fun toggleShuffle() {
        val turningOn = !_shuffle.value
        _shuffle.value = turningOn
        val cur = _current.value
        if (turningOn) {
            if (linear.isEmpty()) linear = _queue.value
            applyShuffledView(current = cur)
        } else {
            val restored = linear.ifEmpty { _queue.value }
            _queue.value = restored
            _index.value = cur?.let { c -> restored.indexOfFirst { it.id == c.id } }
                ?.takeIf { it >= 0 } ?: 0
        }
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
        if (index in _queue.value.indices) loadAt(index, 0L, play = true)
    }

    fun playTrack(track: Track, context: List<Track> = _queue.value.ifEmpty { listOf(track) }) {
        val i = context.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        play(if (context.isNotEmpty()) context else listOf(track), i)
    }

    fun addNext(track: Track) {
        val q = _queue.value.toMutableList()
        val i = (_index.value + 1).coerceIn(0, q.size)
        q.add(i, track)
        _queue.value = q
        val lin = linear.toMutableList()
        val linAt = _current.value?.let { c -> lin.indexOfFirst { it.id == c.id } } ?: lin.lastIndex
        lin.add((linAt + 1).coerceIn(0, lin.size), track)
        linear = lin
        warmSuccessor()
    }

    fun enqueue(track: Track) {
        if (_queue.value.isEmpty() && _current.value == null) {
            play(listOf(track), 0)
            return
        }
        _queue.value = _queue.value + track
        linear = linear + track
        warmSuccessor()
    }

    fun enqueueAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty() && _current.value == null) {
            play(tracks, 0)
            return
        }
        _queue.value = _queue.value + tracks
        linear = linear + tracks
        warmSuccessor()
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        val currentId = _current.value?.id
        _queue.value = q
        if (!_shuffle.value) linear = q
        if (currentId != null) {
            _index.value = q.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        }
        warmSuccessor()
    }

    fun clearQueueKeepCurrent() {
        val cur = _current.value ?: run {
            _queue.value = emptyList()
            linear = emptyList()
            _index.value = 0
            return
        }
        _queue.value = listOf(cur)
        linear = listOf(cur)
        _index.value = 0
        warmSuccessor()
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    fun positionMs(): Long = engine.getPositionMs()
    fun durationMs(): Long = engine.getDurationMs()

    fun release() {
        engine.removeListener(listener)
        engine.release()
    }

    private fun advance(fromUser: Boolean) {
        val target = peekNextIndex(fromUser) ?: return
        val q = _queue.value
        val expected = q.getOrNull(target) ?: return
        val sequential = target == _index.value + 1
        if (sequential && engine.hasPreparedNext() && engine.playPreparedNext()) {
            recordHistory(_current.value)
            applyIndex(target)
            warmSuccessor()
            return
        }
        loadAt(target, 0L, play = true)
    }

    private fun peekNextIndex(fromUser: Boolean): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val i = _index.value + 1
        if (i in q.indices) return i
        if (_repeat.value == RepeatMode.ALL) return 0
        if (fromUser) return null
        return null
    }

    private fun applyShuffledView(current: Track?) {
        val pool = linear.ifEmpty { _queue.value }
        val rest = pool.filter { it.id != current?.id }.shuffled()
        _queue.value = if (current != null) listOf(current) + rest else rest
        _index.value = 0
    }

    private fun applyIndex(i: Int) {
        val track = _queue.value.getOrNull(i) ?: return
        _index.value = i
        _current.value = track
    }

    private fun loadAt(i: Int, startMs: Long, play: Boolean) {
        val q = _queue.value
        val track = q.getOrNull(i) ?: return
        recordHistory(_current.value)
        _index.value = i
        _current.value = track
        val nextIdx = peekNextIndex(fromUser = false)
        val next = nextIdx?.let { q.getOrNull(it) }?.takeIf { it.id != track.id }
        engine.load(track.toPlaybackMedia(), next?.toPlaybackMedia(), startMs)
        if (play) engine.play() else engine.pause()
        warmSuccessor()
    }

    private fun recordHistory(track: Track?) {
        if (track == null) return
        val next = listOf(track) + _history.value.filterNot { it.id == track.id }
        _history.value = next.take(HISTORY_CAP)
    }

    private fun warmSuccessor() {
        val nextIdx = peekNextIndex(fromUser = false) ?: run {
            engine.setNext(null)
            return
        }
        val next = _queue.value.getOrNull(nextIdx) ?: return
        if (next.id == _current.value?.id) {
            engine.setNext(null)
            return
        }
        engine.setNext(next.toPlaybackMedia())
        engine.warmupNext()
    }

    companion object {
        const val RESTART_WINDOW_MS = 3_000L
        private const val HISTORY_CAP = 50
    }
}
