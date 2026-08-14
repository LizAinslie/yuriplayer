package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Dual-queue with consume-on-play semantics + cold [ColdSource] tracking.
 *
 * Pure playback/queue state — UI only observes [snapshot] and issues commands
 * via the service (play, skip, reorder). No UI types or view concerns live here.
 *
 * [playedStack] remembers consumed tracks so Previous can walk back
 * (Spotify-style: >3s restarts current, otherwise previous in play order).
 * Persisted with the rest of the snapshot so Previous still works after restart.
 *
 * Previous does **not** use a side-channel "floating" playhead: the prior track
 * is placed at the head of the active lane and the interrupted track is pushed
 * in right after it, so it shows in the queue UI and is the natural next.
 *
 * [playItem] is the queue-UI jump path only (not album/search/library play):
 *  - Hot: drop every song before the tapped index (not kept in history).
 *  - Cold: move every song before the tapped index into [playedStack].
 */
class QueueManager {

    private val hotQueue = mutableListOf<Song>()
    private var coldOriginal: List<Song> = emptyList()
    private val coldQueue = mutableListOf<Song>()
    private var coldSource: ColdSource? = null
    private var lane = QueueLane.COLD
    private var indexInLane = -1
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF
    /** Only used when hot is cleared while that song is still playing. */
    private var floatingCurrent: Song? = null

    private val playedStack = mutableListOf<Song>()

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

    fun coldSource(): ColdSource? = coldSource

    fun peekNext(): Song? {
        if (repeatMode == RepeatMode.ONE) return null
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
                if (indexInLane in hotQueue.indices && indexInLane < hotQueue.lastIndex) {
                    return hotQueue[indexInLane + 1]
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

    fun peekPrevious(): Song? = playedStack.lastOrNull()

    private fun publish() {
        _snapshot.value = QueueSnapshot(
            hotQueue = hotQueue.toList(),
            coldQueue = coldQueue.toList(),
            coldOriginal = coldOriginal,
            coldSource = coldSource,
            lane = lane,
            indexInLane = indexInLane,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            playedStack = playedStack.toList()
        )
    }

    private fun pushPlayed(song: Song) {
        if (playedStack.lastOrNull()?.let { sameSong(it, song) } == true) return
        playedStack.add(song)
        while (playedStack.size > MAX_PLAYED_STACK) {
            playedStack.removeAt(0)
        }
    }

    /** Detach the currently playing item from lists without consuming into history. */
    private fun detachCurrentWithoutHistory() {
        if (floatingCurrent != null) {
            floatingCurrent = null
            return
        }
        when (lane) {
            QueueLane.HOT -> {
                if (indexInLane in hotQueue.indices) hotQueue.removeAt(indexInLane)
            }
            QueueLane.COLD -> {
                if (indexInLane in coldQueue.indices) coldQueue.removeAt(indexInLane)
            }
        }
        indexInLane = -1
    }

    fun restore(snap: QueueSnapshot) {
        hotQueue.clear()
        hotQueue.addAll(snap.hotQueue)
        coldOriginal = snap.coldOriginal.ifEmpty { snap.coldQueue }
        coldQueue.clear()
        coldQueue.addAll(snap.coldQueue)
        coldSource = snap.coldSource
        lane = snap.lane
        indexInLane = snap.indexInLane
        shuffleEnabled = snap.shuffleEnabled
        repeatMode = snap.repeatMode
        floatingCurrent = null
        playedStack.clear()
        playedStack.addAll(snap.playedStack)
        val max = when (lane) {
            QueueLane.HOT -> hotQueue.lastIndex
            QueueLane.COLD -> coldQueue.lastIndex
        }
        if (max >= 0) indexInLane = indexInLane.coerceIn(0, max)
        publish()
        Log.i(
            TAG,
            "restore lane=$lane source=${coldSource?.type}:${coldSource?.id} " +
                "played=${playedStack.size}"
        )
    }

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        source: ColdSource? = null
    ) {
        if (songs.isEmpty()) return
        Log.i(TAG, "playSource size=${songs.size} start=$startIndex source=$source")
        floatingCurrent = null
        playedStack.clear()
        coldSource = source
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
            if (indexInLane > 0) {
                for (i in 0 until indexInLane) {
                    pushPlayed(coldQueue[i])
                }
                repeat(indexInLane) { coldQueue.removeAt(0) }
                indexInLane = 0
            }
        }
        lane = QueueLane.COLD
        publish()
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String): Boolean {
        val src = coldSource ?: return false
        if (!src.id.equals(sourceId, ignoreCase = true)) return false
        if (songs.isEmpty()) return false

        val current = currentSong()
        val newKeys = songs.map { songKey(it) }.toSet()

        coldOriginal = songs.toList()
        coldQueue.removeAll { songKey(it) !in newKeys }

        val present = coldQueue.map { songKey(it) }.toMutableSet()
        current?.let { present.add(songKey(it)) }

        songs.forEach { s ->
            val k = songKey(s)
            if (k !in present) {
                coldQueue.add(s)
                present.add(k)
            }
        }

        if (lane == QueueLane.COLD && floatingCurrent == null && current != null) {
            val idx = coldQueue.indexOfFirst { sameSong(it, current) }
            if (idx >= 0) indexInLane = idx
            else if (coldQueue.isNotEmpty()) indexInLane = indexInLane.coerceIn(0, coldQueue.lastIndex)
            else indexInLane = -1
        }

        publish()
        Log.i(TAG, "updateColdFromSource id=$sourceId original=${coldOriginal.size} cold=${coldQueue.size}")
        return true
    }

    fun addToQueue(song: Song) {
        hotQueue.add(song)
        publish()
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        hotQueue.addAll(songs)
        publish()
    }

    fun clearHotQueue(): Boolean {
        if (lane == QueueLane.HOT && indexInLane in hotQueue.indices) {
            floatingCurrent = hotQueue[indexInLane]
            hotQueue.clear()
            indexInLane = -1
            publish()
            return false
        }
        hotQueue.clear()
        publish()
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

    /**
     * Queue-UI jump only (not used by album/search/library play).
     *
     * Hot: discard every entry before [index], play the tapped song.
     * Cold: push every entry before [index] into [playedStack], play the tapped song.
     * The interrupted current track (if different) always goes into [playedStack].
     */
    fun playItem(laneTarget: QueueLane, index: Int) {
        when (laneTarget) {
            QueueLane.HOT -> {
                if (index !in hotQueue.indices) return
                val target = hotQueue[index]
                val current = currentSong()
                if (current != null && !sameSong(current, target)) {
                    pushPlayed(current)
                }
                // Drop current out of whichever list it lived in so indices stay sane.
                if (current != null && sameSong(current, target)) {
                    // Already this track — just ensure playhead is on it after trim.
                } else {
                    detachCurrentWithoutHistory()
                }

                // Re-find target after detach (index may have shifted if current was
                // earlier in the same hot list).
                var targetIdx = hotQueue.indexOfFirst { sameSong(it, target) }
                if (targetIdx < 0) {
                    // Detach shouldn't remove the target; safety net.
                    hotQueue.add(0, target)
                    targetIdx = 0
                }

                // Discard everything before the tapped hot item (not history).
                if (targetIdx > 0) {
                    repeat(targetIdx) { hotQueue.removeAt(0) }
                }

                floatingCurrent = null
                lane = QueueLane.HOT
                indexInLane = 0
                publish()
                Log.i(
                    TAG,
                    "playItem HOT → '${target.displayTitle}' " +
                        "hotLeft=${hotQueue.size} played=${playedStack.size}"
                )
            }
            QueueLane.COLD -> {
                if (index !in coldQueue.indices) return
                val target = coldQueue[index]
                val current = currentSong()
                if (current != null && !sameSong(current, target)) {
                    pushPlayed(current)
                }
                if (current == null || !sameSong(current, target)) {
                    detachCurrentWithoutHistory()
                }

                var targetIdx = coldQueue.indexOfFirst { sameSong(it, target) }
                if (targetIdx < 0) {
                    coldQueue.add(0, target)
                    targetIdx = 0
                }

                // Spotify: jumped-over cold tracks stay reachable via Previous.
                if (targetIdx > 0) {
                    for (i in 0 until targetIdx) {
                        pushPlayed(coldQueue[i])
                    }
                    repeat(targetIdx) { coldQueue.removeAt(0) }
                }

                floatingCurrent = null
                lane = QueueLane.COLD
                indexInLane = 0
                publish()
                Log.i(
                    TAG,
                    "playItem COLD → '${target.displayTitle}' " +
                        "coldLeft=${coldQueue.size} played=${playedStack.size}"
                )
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        val current = currentSong()
        if (!enabled) {
            if (!shuffleEnabled) return
            shuffleEnabled = false
            coldQueue.clear()
            coldQueue.addAll(coldOriginal)
            if (current != null && lane == QueueLane.COLD && floatingCurrent == null) {
                val idx = coldQueue.indexOfFirst { sameSong(it, current) }
                indexInLane = if (idx >= 0) idx else 0
            }
            publish()
            return
        }
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
        publish()
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        publish()
    }

    fun advance(userInitiated: Boolean): AdvanceResult {
        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            return AdvanceResult(song = currentSong(), reload = true)
        }

        val wasFloating = floatingCurrent != null
        val fromLane = lane
        val fromIndex = indexInLane

        if (floatingCurrent != null) {
            pushPlayed(floatingCurrent!!)
            floatingCurrent = null
        } else {
            when (fromLane) {
                QueueLane.HOT -> {
                    if (fromIndex in hotQueue.indices) {
                        pushPlayed(hotQueue.removeAt(fromIndex))
                    }
                }
                QueueLane.COLD -> {
                    if (fromIndex in coldQueue.indices) {
                        pushPlayed(coldQueue.removeAt(fromIndex))
                    }
                }
            }
        }

        if (wasFloating) {
            return resolveNextFromHeads()
        }

        // After removing current at fromIndex, the next item in that lane
        // slides into fromIndex (list shrinks left).
        if (fromLane == QueueLane.HOT && fromIndex in hotQueue.indices) {
            lane = QueueLane.HOT
            indexInLane = fromIndex
            publish()
            return AdvanceResult(song = hotQueue[indexInLane])
        }

        if (hotQueue.isNotEmpty() && fromLane != QueueLane.HOT) {
            lane = QueueLane.HOT
            indexInLane = 0
            publish()
            return AdvanceResult(song = hotQueue[0])
        }

        if (fromLane == QueueLane.COLD && fromIndex in coldQueue.indices) {
            lane = QueueLane.COLD
            indexInLane = fromIndex
            publish()
            return AdvanceResult(song = coldQueue[indexInLane])
        }

        if (coldQueue.isNotEmpty()) {
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }

        if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
            repopulateCold()
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }

        indexInLane = -1
        publish()
        return AdvanceResult(finished = true)
    }

    private fun resolveNextFromHeads(): AdvanceResult {
        if (hotQueue.isNotEmpty()) {
            lane = QueueLane.HOT
            indexInLane = 0
            publish()
            return AdvanceResult(song = hotQueue[0])
        }
        if (coldQueue.isNotEmpty()) {
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }
        if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty()) {
            repopulateCold()
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }
        indexInLane = -1
        publish()
        return AdvanceResult(finished = true)
    }

    /**
     * Previous track: place [prev] at the head of the active lane as the new
     * current, and re-insert the interrupted track immediately after it so it
     * appears in the queue UI and is the natural next on advance.
     */
    fun skipPrevious(currentPositionMs: Long): AdvanceResult {
        val current = currentSong()
        if (currentPositionMs > PREV_RESTART_MS || playedStack.isEmpty()) {
            return AdvanceResult(song = current, seekToStart = true)
        }

        val prev = playedStack.removeAt(playedStack.lastIndex)

        // Detach whatever is currently playing from the lists
        if (floatingCurrent != null) {
            floatingCurrent = null
        } else {
            when (lane) {
                QueueLane.HOT -> {
                    if (indexInLane in hotQueue.indices) hotQueue.removeAt(indexInLane)
                }
                QueueLane.COLD -> {
                    if (indexInLane in coldQueue.indices) coldQueue.removeAt(indexInLane)
                }
            }
        }

        val useCold = coldSource != null || coldQueue.isNotEmpty() || coldOriginal.isNotEmpty()
        if (useCold) {
            // [prev (now playing), interrupted current, ...rest]
            if (current != null) coldQueue.add(0, current)
            coldQueue.add(0, prev)
            lane = QueueLane.COLD
            indexInLane = 0
        } else {
            if (current != null) hotQueue.add(0, current)
            hotQueue.add(0, prev)
            lane = QueueLane.HOT
            indexInLane = 0
        }
        floatingCurrent = null

        publish()
        Log.i(
            TAG,
            "skipPrevious → '${prev.displayTitle}' next='${current?.displayTitle}' " +
                "lane=$lane cold=${coldQueue.size} hot=${hotQueue.size} " +
                "playedLeft=${playedStack.size}"
        )
        return AdvanceResult(song = prev)
    }

    private fun repopulateCold() {
        coldQueue.clear()
        if (shuffleEnabled) {
            coldQueue.addAll(coldOriginal.shuffled(Random(System.nanoTime())))
        } else {
            coldQueue.addAll(coldOriginal)
        }
        playedStack.clear()
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

    private fun songKey(s: Song): String =
        s.path?.lowercase() ?: s.contentUri.toString()

    data class AdvanceResult(
        val song: Song? = null,
        val finished: Boolean = false,
        val reload: Boolean = false,
        val seekToStart: Boolean = false
    )

    companion object {
        private const val TAG = "YuriPlayer.Queue"
        private const val PREV_RESTART_MS = 3_000L
        private const val MAX_PLAYED_STACK = 200
    }
}
