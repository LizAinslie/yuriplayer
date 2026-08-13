package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual-queue source of truth (user Queue + context/album).
 * No Android framework deps — [MusicService] drives ExoPlayer from [AdvanceResult].
 */
class QueueManager {

    private val hotQueue = mutableListOf<Song>()
    private var coldOriginal: List<Song> = emptyList()
    private val coldQueue = mutableListOf<Song>()
    private var lane = QueueLane.COLD
    private var indexInLane = -1
    private var coldResumeIndex = 0
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    fun currentSong(): Song? = when (lane) {
        QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
        QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
    }

    fun getSnapshot(): QueueSnapshot = _snapshot.value

    /** Next song that [advance] would load (no mutation). Null if playback would stop. */
    fun peekNext(): Song? {
        if (repeatMode == RepeatMode.ONE) return currentSong()
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane < hotQueue.lastIndex) return hotQueue[indexInLane + 1]
                if (coldQueue.isNotEmpty()) {
                    val idx = coldResumeIndex.coerceIn(0, coldQueue.lastIndex)
                    if (coldResumeIndex < coldQueue.size) return coldQueue[idx]
                    if (repeatMode == RepeatMode.COLD) return coldQueue.firstOrNull()
                    return null
                }
                if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
                    return if (shuffleEnabled) coldOriginal.random() else coldOriginal.first()
                }
                return null
            }
            QueueLane.COLD -> {
                if (hotQueue.isNotEmpty()) return hotQueue.first()
                if (indexInLane < coldQueue.lastIndex) return coldQueue[indexInLane + 1]
                if (repeatMode == RepeatMode.COLD && coldQueue.isNotEmpty()) return coldQueue.first()
                return null
            }
        }
    }

    /** Previous song [skipPrevious] would load when position ≤ 3s (no mutation). */
    fun peekPrevious(): Song? {
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane > 0) return hotQueue[indexInLane - 1]
                val prevCold = (coldResumeIndex - 1).coerceAtLeast(0)
                return coldQueue.getOrNull(prevCold)
            }
            QueueLane.COLD -> {
                if (indexInLane > 0) return coldQueue[indexInLane - 1]
                return currentSong()
            }
        }
    }

    private fun publish() {
        _snapshot.value = QueueSnapshot(
            hotQueue = hotQueue.toList(),
            coldQueue = coldQueue.toList(),
            coldOriginal = coldOriginal,
            lane = lane,
            indexInLane = indexInLane,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    fun restore(snap: QueueSnapshot) {
        hotQueue.clear()
        hotQueue.addAll(snap.hotQueue)
        coldOriginal = snap.coldOriginal.ifEmpty { snap.coldQueue }
        coldQueue.clear()
        coldQueue.addAll(snap.coldQueue)
        lane = snap.lane
        indexInLane = snap.indexInLane
        shuffleEnabled = snap.shuffleEnabled
        repeatMode = snap.repeatMode
        coldResumeIndex = when (lane) {
            QueueLane.COLD -> indexInLane
            QueueLane.HOT -> indexInLane
        }
        val max = when (lane) {
            QueueLane.HOT -> hotQueue.lastIndex
            QueueLane.COLD -> coldQueue.lastIndex
        }
        if (max >= 0) indexInLane = indexInLane.coerceIn(0, max)
        publish()
        Log.i(TAG, "restore lane=$lane index=$indexInLane queue=${hotQueue.size} cold=${coldQueue.size}")
    }

    fun playSource(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        Log.i(TAG, "playSource size=${songs.size} start=$startIndex")
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
        publish()
    }

    fun addToQueue(song: Song) {
        Log.i(TAG, "queue add path=${song.path} title='${song.displayTitle}'")
        hotQueue.add(song)
        publish()
    }

    fun addToQueue(songs: List<Song>) {
        hotQueue.addAll(songs)
        publish()
    }

    fun removeFromQueue(index: Int): Boolean {
        if (index !in hotQueue.indices) return false
        val removingCurrent = lane == QueueLane.HOT && index == indexInLane
        hotQueue.removeAt(index)
        if (lane == QueueLane.HOT) {
            when {
                hotQueue.isEmpty() -> {
                    resumeColdPointer()
                    publish()
                    return removingCurrent
                }
                index < indexInLane -> indexInLane--
                removingCurrent -> indexInLane = indexInLane.coerceAtMost(hotQueue.lastIndex)
            }
        }
        publish()
        return removingCurrent
    }

    fun removeFromContext(index: Int): Boolean {
        if (index !in coldQueue.indices) return false
        val removingCurrent = lane == QueueLane.COLD && index == indexInLane
        val song = coldQueue.removeAt(index)
        coldOriginal = coldOriginal.filterNot { sameSong(it, song) }
        if (index < coldResumeIndex) coldResumeIndex = (coldResumeIndex - 1).coerceAtLeast(0)
        if (lane == QueueLane.COLD) {
            when {
                coldQueue.isEmpty() -> {
                    indexInLane = -1
                    publish()
                    return removingCurrent
                }
                index < indexInLane -> indexInLane--
                removingCurrent -> { }
            }
        }
        publish()
        return removingCurrent
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from !in hotQueue.indices || to !in hotQueue.indices || from == to) return
        val item = hotQueue.removeAt(from)
        hotQueue.add(to, item)
        if (lane == QueueLane.HOT) indexInLane = remapIndex(indexInLane, from, to)
        publish()
    }

    fun moveInContext(from: Int, to: Int) {
        if (from !in coldQueue.indices || to !in coldQueue.indices || from == to) return
        val item = coldQueue.removeAt(from)
        coldQueue.add(to, item)
        if (lane == QueueLane.COLD) indexInLane = remapIndex(indexInLane, from, to)
        publish()
    }

    fun playItem(laneTarget: QueueLane, index: Int) {
        when (laneTarget) {
            QueueLane.HOT -> {
                if (index !in hotQueue.indices) return
                if (lane == QueueLane.COLD) coldResumeIndex = indexInLane + 1
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
        publish()
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        Log.i(TAG, "setShuffle $enabled")
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
        publish()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.COLD
            RepeatMode.COLD -> RepeatMode.OFF
        }
        Log.i(TAG, "repeat -> $repeatMode")
        publish()
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        publish()
    }

    fun advance(userInitiated: Boolean): AdvanceResult {
        Log.i(
            TAG,
            "advance user=$userInitiated repeat=$repeatMode lane=$lane index=$indexInLane " +
                "queue=${hotQueue.size} cold=${coldQueue.size} resume=$coldResumeIndex " +
                "path=${currentSong()?.path}"
        )

        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            val song = currentSong()
            Log.i(TAG, "repeat ONE path=${song?.path}")
            return AdvanceResult(song = song, reload = true)
        }

        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane < hotQueue.lastIndex) {
                    indexInLane++
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                hotQueue.clear()
                return resumeColdResult()
            }
            QueueLane.COLD -> {
                if (hotQueue.isNotEmpty()) {
                    coldResumeIndex = (indexInLane + 1).coerceAtMost(coldQueue.size)
                    lane = QueueLane.HOT
                    indexInLane = 0
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                if (indexInLane < coldQueue.lastIndex) {
                    indexInLane++
                    coldResumeIndex = indexInLane
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                if (repeatMode == RepeatMode.COLD && coldQueue.isNotEmpty()) {
                    indexInLane = 0
                    coldResumeIndex = 0
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                publish()
                return AdvanceResult(finished = true)
            }
        }
    }

    fun skipPrevious(currentPositionMs: Long): AdvanceResult {
        if (currentPositionMs > 3000L) {
            return AdvanceResult(song = currentSong(), seekToStart = true)
        }
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane > 0) {
                    indexInLane--
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                val prevCold = (coldResumeIndex - 1).coerceAtLeast(0)
                if (coldQueue.isNotEmpty()) {
                    lane = QueueLane.COLD
                    indexInLane = prevCold
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                return AdvanceResult(song = currentSong(), seekToStart = true)
            }
            QueueLane.COLD -> {
                if (indexInLane > 0) {
                    indexInLane--
                    coldResumeIndex = indexInLane
                    publish()
                    return AdvanceResult(song = currentSong())
                }
                return AdvanceResult(song = currentSong(), seekToStart = true)
            }
        }
    }

    private fun resumeColdPointer() {
        lane = QueueLane.COLD
        if (coldQueue.isEmpty()) {
            indexInLane = -1
            return
        }
        if (coldResumeIndex >= coldQueue.size) {
            if (repeatMode == RepeatMode.COLD) {
                indexInLane = 0
                coldResumeIndex = 0
            } else {
                indexInLane = coldQueue.lastIndex
            }
        } else {
            indexInLane = coldResumeIndex.coerceIn(0, coldQueue.lastIndex)
        }
    }

    private fun resumeColdResult(): AdvanceResult {
        resumeColdPointer()
        publish()
        val song = currentSong()
        if (song == null) return AdvanceResult(finished = true)
        if (coldResumeIndex >= coldQueue.size && repeatMode != RepeatMode.COLD) {
            return AdvanceResult(finished = true, song = song)
        }
        return AdvanceResult(song = song)
    }

    private fun remapIndex(current: Int, from: Int, to: Int): Int = when {
        current == from -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    private fun sameSong(a: Song, b: Song): Boolean {
        if (a.path != null && b.path != null) return a.path == b.path
        return a.contentUri == b.contentUri || a.id == b.id
    }

    data class AdvanceResult(
        val song: Song? = null,
        val finished: Boolean = false,
        val reload: Boolean = false,
        val seekToStart: Boolean = false
    )

    companion object {
        private const val TAG = "YuriPlayer.Queue"
    }
}
