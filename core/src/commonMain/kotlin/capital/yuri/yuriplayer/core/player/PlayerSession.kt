package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-side queue + now-playing. Engines only produce sound.
 * Previous within [RESTART_WINDOW_MS] seeks to 0 (Spotify 3s rule).
 */
class PlayerSession(
    private val engine: PlaybackEngine
) {
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
                RepeatMode.ALL -> {
                    val q = _queue.value
                    if (q.isEmpty()) return
                    if (!engine.playPreparedNext()) {
                        val i = _index.value + 1
                        if (i in q.indices) next()
                        else loadAt(0, 0L, play = true)
                    }
                }
                RepeatMode.OFF -> {
                    if (!engine.playPreparedNext()) next()
                }
            }
        }

        override fun onAutoAdvanced() {
            val q = _queue.value
            val i = _index.value + 1
            if (i in q.indices) {
                recordHistory(_current.value)
                _index.value = i
                _current.value = q[i]
                warmSuccessor()
            }
        }
    }

    init {
        engine.addListener(listener)
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        _queue.value = tracks
        loadAt(startIndex.coerceIn(tracks.indices), 0L, play = true)
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

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (engine.playPreparedNext()) {
            recordHistory(_current.value)
            val i = (_index.value + 1).coerceAtMost(q.lastIndex)
            _index.value = i
            _current.value = q[i]
            warmSuccessor()
            return
        }
        val i = _index.value + 1
        if (i in q.indices) loadAt(i, 0L, play = true)
    }

    fun previous() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (engine.getPositionMs() > RESTART_WINDOW_MS) {
            engine.seekTo(0)
            return
        }
        val i = _index.value - 1
        if (i in q.indices) loadAt(i, 0L, play = true)
        else engine.seekTo(0)
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        if (_shuffle.value) {
            val cur = _current.value
            val rest = _queue.value.filter { it.id != cur?.id }.shuffled()
            if (cur != null) {
                _queue.value = listOf(cur) + rest
                _index.value = 0
            } else {
                _queue.value = _queue.value.shuffled()
            }
        }
    }

    fun cycleRepeat() {
        _repeat.value = _repeat.value.next()
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
        warmSuccessor()
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        val currentId = _current.value?.id
        _queue.value = q
        if (currentId != null) {
            _index.value = q.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        }
        warmSuccessor()
    }

    fun clearQueueKeepCurrent() {
        val cur = _current.value ?: run {
            _queue.value = emptyList()
            _index.value = 0
            return
        }
        _queue.value = listOf(cur)
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

    private fun loadAt(i: Int, startMs: Long, play: Boolean) {
        val q = _queue.value
        val track = q.getOrNull(i) ?: return
        recordHistory(_current.value)
        _index.value = i
        _current.value = track
        val next = q.getOrNull(i + 1)
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
        val next = _queue.value.getOrNull(_index.value + 1) ?: return
        engine.setNext(next.toPlaybackMedia())
        engine.warmupNext()
    }

    companion object {
        const val RESTART_WINDOW_MS = 3_000L
        private const val HISTORY_CAP = 50
    }
}
