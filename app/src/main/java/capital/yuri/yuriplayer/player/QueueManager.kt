package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Dual-queue with consume-on-play semantics.
 *
 * - Tracks are **removed** from hot/cold as they finish (or are skipped past).
 * - [coldOriginal] is the refill source for Repeat all (reshuffled when shuffle is on).
 * - [floatingCurrent] holds a track pulled out of the lists (e.g. after Clear) while
 *   it continues playing; next advance goes to cold.
 */
class QueueManager {

    private val hotQueue = mutableListOf<Song>()
    private var coldOriginal: List<Song> = emptyList()
    private val coldQueue = mutableListOf<Song>()
    private var lane = QueueLane.COLD
    private var indexInLane = -1
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF
    /** Playing but not in any list (cleared out of hot, etc.). */
    private var floatingCurrent: Song? = null

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    fun currentSong(): Song? {
        floatingCurrent?.let { return it }
        return when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }
    }

    fun getSnapshot(): QueueSnapshot = _snapshot.value

    fun peekNext(): Song? {
        if (repeatMode == RepeatMode.ONE) return currentSong()
        val cur = currentSong()
        // Next after current in hot
        if (floatingCurrent != null) {
            if (hotQueue.isNotEmpty()) return hotQueue.first()
            if (coldQueue.isNotEmpty()) return coldQueue.first()
            if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
                return if (shuffleEnabled) coldOriginal.random() else coldOriginal.first()
            }
            return null
        }
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane in hotQueue.indices) {
                    if (indexInLane < hotQueue.lastIndex) return hotQueue[indexInLane + 1]
                }
                if (coldQueue.isNotEmpty()) return coldQueue.first()
                if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
                    return if (shuffleEnabled) coldOriginal.random() else coldOriginal.first()
                }
                return null
            }
            QueueLane.COLD -> {
                if (hotQueue.isNotEmpty()) return hotQueue.first()
                if (indexInLane in coldQueue.indices && indexInLane < coldQueue.lastIndex) {
                    return coldQueue[indexInLane + 1]
                }
                if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
                    return if (shuffleEnabled) coldOriginal.random() else coldOriginal.first()
                }
                return null
            }
        }
    }

    fun peekPrevious(): Song? = currentSong()

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
        floatingCurrent = null
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
        floatingCurrent = null
        coldOriginal = songs.toList()
        coldQueue.clear()
        if (shuffleEnabled) {
            coldQueue.addAll(songs.shuffled(Random(System.nanoTime())))
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
        publish()
    }

    fun addToQueue(song: Song) {
        Log.i(TAG, "queue add path=${song.path} title='${song.displayTitle}'")
        hotQueue.add(song)
        publish()
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        Log.i(TAG, "queue add ${songs.size} tracks")
        hotQueue.addAll(songs)
        publish()
    }

    /**
     * Clear pending user-queue items.
     * If currently playing from hot, that track is **pulled out** (floating) so it
     * keeps playing; it is not in the list anymore. Next advance → cold.
     * @return always false (never forces an immediate reload).
     */
    fun clearHotQueue(): Boolean {
        if (lane == QueueLane.HOT && indexInLane in hotQueue.indices) {
            floatingCurrent = hotQueue[indexInLane]
            hotQueue.clear()
            indexInLane = -1
            publish()
            Log.i(TAG, "clearHot → floating '${floatingCurrent?.displayTitle}'")
            return false
        }
        hotQueue.clear()
        publish()
        Log.i(TAG, "clearHot")
        return false
    }

    fun removeFromQueue(index: Int): Boolean {
        if (index !in hotQueue.indices) return false
        val removingCurrent = floatingCurrent == null && lane == QueueLane.HOT && index == indexInLane
        hotQueue.removeAt(index)
        if (lane == QueueLane.HOT && floatingCurrent == null) {
            when {
                hotQueue.isEmpty() -> {
                    indexInLane = -1
                    publish()
                    return removingCurrent
                }
                index < indexInLane -> indexInLane--
                removingCurrent -> {
                    // fall through — caller reloads next
                    if (indexInLane > hotQueue.lastIndex) indexInLane = hotQueue.lastIndex
                }
            }
        }
        publish()
        return removingCurrent
    }

    fun removeFromContext(index: Int): Boolean {
        if (index !in coldQueue.indices) return false
        val removingCurrent = floatingCurrent == null && lane == QueueLane.COLD && index == indexInLane
        coldQueue.removeAt(index)
        if (lane == QueueLane.COLD && floatingCurrent == null) {
            when {
                coldQueue.isEmpty() -> {
                    indexInLane = -1
                    publish()
                    return removingCurrent
                }
                index < indexInLane -> indexInLane--
                removingCurrent -> {
                    if (indexInLane > coldQueue.lastIndex) indexInLane = coldQueue.lastIndex
                }
            }
        }
        publish()
        return removingCurrent
    }

    fun moveColdToHot(coldIndex: Int): Boolean {
        if (coldIndex !in coldQueue.indices) return false
        val removingCurrent = floatingCurrent == null && lane == QueueLane.COLD && coldIndex == indexInLane
        val song = coldQueue.removeAt(coldIndex)
        if (lane == QueueLane.COLD && floatingCurrent == null) {
            when {
                coldQueue.isEmpty() -> indexInLane = -1
                coldIndex < indexInLane -> indexInLane--
                removingCurrent -> indexInLane = indexInLane.coerceAtMost(coldQueue.lastIndex)
            }
        }
        hotQueue.add(song)
        publish()
        return removingCurrent
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from !in hotQueue.indices || to !in hotQueue.indices || from == to) return
        val item = hotQueue.removeAt(from)
        hotQueue.add(to, item)
        if (lane == QueueLane.HOT && floatingCurrent == null) {
            indexInLane = remapIndex(indexInLane, from, to)
        }
        publish()
    }

    fun moveInContext(from: Int, to: Int) {
        if (from !in coldQueue.indices || to !in coldQueue.indices || from == to) return
        val item = coldQueue.removeAt(from)
        coldQueue.add(to, item)
        if (lane == QueueLane.COLD && floatingCurrent == null) {
            indexInLane = remapIndex(indexInLane, from, to)
        }
        publish()
    }

    fun playItem(laneTarget: QueueLane, index: Int) {
        floatingCurrent = null
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
        publish()
    }

    fun setShuffle(enabled: Boolean) {
        val current = currentSong()
        if (!enabled) {
            if (!shuffleEnabled) return
            Log.i(TAG, "setShuffle OFF")
            shuffleEnabled = false
            // Rebuild remaining cold from original order, keep current at front if in cold
            coldQueue.clear()
            coldQueue.addAll(coldOriginal)
            if (current != null && lane == QueueLane.COLD && floatingCurrent == null) {
                val idx = coldQueue.indexOfFirst { sameSong(it, current) }
                indexInLane = if (idx >= 0) idx else 0
            }
            publish()
            return
        }
        Log.i(TAG, "setShuffle ON (reshuffle)")
        shuffleEnabled = true
        val shuffled = coldOriginal.shuffled(Random(System.nanoTime())).toMutableList()
        if (current != null && (lane == QueueLane.COLD || floatingCurrent != null)) {
            shuffled.removeAll { sameSong(it, current) }
            if (floatingCurrent == null && lane == QueueLane.COLD) {
                shuffled.add(0, current)
                coldQueue.clear()
                coldQueue.addAll(shuffled)
                indexInLane = 0
            } else {
                coldQueue.clear()
                coldQueue.addAll(shuffled)
            }
        } else {
            coldQueue.clear()
            coldQueue.addAll(shuffled)
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

    /**
     * Advance: consume the current track from its list, then pick the next.
     * Repeat-one (natural end) reloads without consuming.
     */
    fun advance(userInitiated: Boolean): AdvanceResult {
        Log.i(
            TAG,
            "advance user=$userInitiated repeat=$repeatMode lane=$lane index=$indexInLane " +
                "queue=${hotQueue.size} cold=${coldQueue.size} floating=${floatingCurrent != null} " +
                "path=${currentSong()?.path}"
        )

        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            val song = currentSong()
            Log.i(TAG, "repeat ONE path=${song?.path}")
            return AdvanceResult(song = song, reload = true)
        }

        // Consume current from list
        if (floatingCurrent != null) {
            floatingCurrent = null
        } else {
            when (lane) {
                QueueLane.HOT -> {
                    if (indexInLane in hotQueue.indices) {
                        hotQueue.removeAt(indexInLane)
                        // indexInLane now points at what was next, or past end
                    }
                }
                QueueLane.COLD -> {
                    if (indexInLane in coldQueue.indices) {
                        coldQueue.removeAt(indexInLane)
                    }
                }
            }
        }

        // Prefer remaining hot queue
        if (hotQueue.isNotEmpty()) {
            lane = QueueLane.HOT
            indexInLane = 0
            publish()
            return AdvanceResult(song = hotQueue[0])
        }

        // Then remaining cold
        if (coldQueue.isNotEmpty()) {
            lane = QueueLane.COLD
            // After remove, same index is the next track; clamp
            indexInLane = indexInLane.coerceIn(0, coldQueue.lastIndex)
            publish()
            return AdvanceResult(song = coldQueue[indexInLane])
        }

        // Refill cold on Repeat all
        if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
            repopulateCold()
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            Log.i(TAG, "repeat all refill size=${coldQueue.size} shuffle=$shuffleEnabled")
            return AdvanceResult(song = coldQueue[0])
        }

        indexInLane = -1
        publish()
        return AdvanceResult(finished = true)
    }

    fun skipPrevious(currentPositionMs: Long): AdvanceResult {
        // Consumed tracks are gone — only restart current if >3s in, else no-op restart
        return AdvanceResult(song = currentSong(), seekToStart = true)
    }

    private fun repopulateCold() {
        coldQueue.clear()
        if (shuffleEnabled) {
            coldQueue.addAll(coldOriginal.shuffled(Random(System.nanoTime())))
        } else {
            coldQueue.addAll(coldOriginal)
        }
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
