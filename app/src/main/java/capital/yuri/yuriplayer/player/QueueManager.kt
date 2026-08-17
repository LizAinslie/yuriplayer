package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Dual-queue + optional radio session.
 *
 * Radio cold queue is planned by [RadioEngine] (visible on queue page).
 * After each advance (finish/skip), [maybeRestockRadio] tops up:
 *   shuffle → random tracks until maxRadioQueue
 *   ordered → next whole release only when cold size < max - 1
 * Repeat-all is disabled while radio is active.
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
    private var floatingCurrent: Song? = null
    private val playedStack = mutableListOf<Song>()

    private var radioSession: RadioSession? = null
    private var radioUpcoming: List<Song> = emptyList()

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<QueueEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<QueueEvent> = _events.asSharedFlow()

    @Volatile
    var autoPlayHelper: MusicServiceAutoPlay? = null

    private fun emit(event: QueueEvent) {
        _events.tryEmit(event)
    }

    fun currentSong(): Song? {
        floatingCurrent?.let { return it }
        return when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }
    }

    fun getSnapshot(): QueueSnapshot = _snapshot.value
    fun coldSource(): ColdSource? = coldSource
    fun isRadioActive(): Boolean = radioSession?.active == true

    fun setRadioSession(session: RadioSession?) {
        radioSession = session
        if (session != null) {
            shuffleEnabled = session.prefs.shuffle
            if (repeatMode == RepeatMode.COLD) repeatMode = RepeatMode.OFF
        }
        publish()
    }

    fun setRadioUpcoming(songs: List<Song>) {
        radioUpcoming = songs
        publish()
    }

    fun clearRadio() {
        radioSession = null
        radioUpcoming = emptyList()
        autoPlayHelper?.stopRadio()
        publish()
    }

    fun peekNext(): Song? {
        if (repeatMode == RepeatMode.ONE) return null
        if (floatingCurrent != null) {
            if (hotQueue.isNotEmpty()) return hotQueue.first()
            if (coldQueue.isNotEmpty()) return coldQueue.first()
            if (radioUpcoming.isNotEmpty()) return radioUpcoming.first()
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
                if (radioUpcoming.isNotEmpty()) return radioUpcoming.first()
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
                if (radioUpcoming.isNotEmpty()) return radioUpcoming.first()
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
            playedStack = playedStack.toList(),
            radioSession = radioSession,
            radioUpcoming = radioUpcoming
        )
    }

    private fun pushPlayed(song: Song) {
        if (playedStack.lastOrNull()?.let { sameSong(it, song) } == true) return
        playedStack.add(song)
        while (playedStack.size > MAX_PLAYED_STACK) {
            playedStack.removeAt(0)
        }
    }

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

    private fun tryAutoPlayRescue(seed: Song?, source: ColdSource?): AdvanceResult? {
        val helper = autoPlayHelper ?: return null
        val pick = helper.maybePick(seed, source, repeatMode) ?: return null
        val songs = pick.songs
        if (songs.isEmpty()) return null
        Log.i(TAG, "radio/auto-play rescue → ${songs.size} tracks (${pick.source.title})")
        if (pick.session != null) {
            radioSession = pick.session
            shuffleEnabled = pick.session.prefs.shuffle
            if (repeatMode == RepeatMode.COLD) repeatMode = RepeatMode.OFF
        }
        radioUpcoming = pick.upcoming
        playSource(songs, startIndex = 0, source = pick.source, keepRadio = true)
        val next = currentSong() ?: return null
        return AdvanceResult(song = next)
    }

    /**
     * After consuming a radio track (finish or skip), top up cold queue.
     * Shuffle → random tracks to max; ordered → next release if size < max - 1.
     */
    private fun maybeRestockRadio() {
        val sess = radioSession?.takeIf { it.active } ?: return
        val helper = autoPlayHelper ?: return
        val keys = buildSet {
            coldQueue.forEach { add(songKey(it)) }
            currentSong()?.let { add(songKey(it)) }
            floatingCurrent?.let { add(songKey(it)) }
            playedStack.takeLast(40).forEach { add(songKey(it)) }
        }
        val add = helper.restock(coldQueue.size, keys)
        if (add.isEmpty()) return
        coldQueue.addAll(add)
        coldOriginal = coldOriginal + add
        Log.i(
            TAG,
            "radio restock +${add.size} cold=${coldQueue.size} " +
                "shuffle=${sess.prefs.shuffle}"
        )
        publish()
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
        radioSession = snap.radioSession
        radioUpcoming = snap.radioUpcoming
        if (radioSession?.active == true && repeatMode == RepeatMode.COLD) {
            repeatMode = RepeatMode.OFF
        }
        val max = when (lane) {
            QueueLane.HOT -> hotQueue.lastIndex
            QueueLane.COLD -> coldQueue.lastIndex
        }
        if (max >= 0) indexInLane = indexInLane.coerceIn(0, max)
        publish()
    }

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        source: ColdSource? = null,
        keepRadio: Boolean = false
    ) {
        if (songs.isEmpty()) return
        Log.i(TAG, "playSource size=${songs.size} start=$startIndex source=$source keepRadio=$keepRadio")
        floatingCurrent = null
        playedStack.clear()
        coldSource = source

        if (!keepRadio && source?.type != ColdSourceType.RADIO) {
            radioSession = null
            radioUpcoming = emptyList()
            autoPlayHelper?.stopRadio()
        }

        coldOriginal = songs.toList()
        coldQueue.clear()

        // Radio batches are already ordered by RadioEngine — do not reshuffle here.
        val isRadio = source?.type == ColdSourceType.RADIO || keepRadio
        if (!isRadio && shuffleEnabled) {
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
        emit(
            QueueEvent.SourceChanged(
                source = source,
                songCount = songs.size,
                startSong = currentSong()
            )
        )
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String): Boolean {
        val src = coldSource ?: return false
        if (!src.id.equals(sourceId, ignoreCase = true)) return false
        if (songs.isEmpty()) return false
        if (src.type == ColdSourceType.RADIO) return false

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

    fun playItem(laneTarget: QueueLane, index: Int) {
        when (laneTarget) {
            QueueLane.HOT -> {
                if (index !in hotQueue.indices) return
                val target = hotQueue[index]
                val current = currentSong()
                if (current != null && !sameSong(current, target)) pushPlayed(current)
                if (current == null || !sameSong(current, target)) detachCurrentWithoutHistory()

                var targetIdx = hotQueue.indexOfFirst { sameSong(it, target) }
                if (targetIdx < 0) {
                    hotQueue.add(0, target)
                    targetIdx = 0
                }
                if (targetIdx > 0) repeat(targetIdx) { hotQueue.removeAt(0) }

                floatingCurrent = null
                lane = QueueLane.HOT
                indexInLane = 0
                publish()
            }
            QueueLane.COLD -> {
                if (index !in coldQueue.indices) return
                val target = coldQueue[index]
                val current = currentSong()
                if (current != null && !sameSong(current, target)) pushPlayed(current)
                if (current == null || !sameSong(current, target)) detachCurrentWithoutHistory()

                var targetIdx = coldQueue.indexOfFirst { sameSong(it, target) }
                if (targetIdx < 0) {
                    coldQueue.add(0, target)
                    targetIdx = 0
                }
                if (targetIdx > 0) {
                    for (i in 0 until targetIdx) pushPlayed(coldQueue[i])
                    repeat(targetIdx) { coldQueue.removeAt(0) }
                }

                floatingCurrent = null
                lane = QueueLane.COLD
                indexInLane = 0
                publish()
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        // Radio shuffle is a source preference — absolute set via engine (no-op if already correct).
        if (radioSession?.active == true) {
            val helper = autoPlayHelper
            val next = helper?.radioEngine?.setShufflePrefs(enabled)
            if (next != null) {
                radioSession = radioSession?.copy(prefs = next)
                shuffleEnabled = next.shuffle
                // Re-plan cold queue from engine
                val batch = helper.radioEngine.planBatch()
                if (batch != null && batch.songs.isNotEmpty()) {
                    val cur = currentSong()
                    val ordered = batch.songs.toMutableList()
                    if (cur != null) {
                        ordered.removeAll { sameSong(it, cur) }
                        coldOriginal = listOf(cur) + ordered
                        coldQueue.clear()
                        coldQueue.add(cur)
                        coldQueue.addAll(ordered)
                        lane = QueueLane.COLD
                        indexInLane = 0
                        floatingCurrent = null
                    } else {
                        playSource(batch.songs, 0, batch.source, keepRadio = true)
                    }
                }
                publish()
                emit(QueueEvent.ShuffleChanged(next.shuffle))
            }
            return
        }

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
            emit(QueueEvent.ShuffleChanged(false))
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
        emit(QueueEvent.ShuffleChanged(true))
    }

    fun cycleRepeatMode() {
        repeatMode = if (radioSession?.active == true) {
            // No repeat-all in radio mode
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        } else {
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.COLD
                RepeatMode.COLD -> RepeatMode.OFF
            }
        }
        publish()
        emit(QueueEvent.RepeatModeChanged(repeatMode))
    }

    fun setRepeatMode(mode: RepeatMode) {
        val resolved = if (radioSession?.active == true && mode == RepeatMode.COLD) {
            RepeatMode.OFF
        } else mode
        if (repeatMode == resolved) return
        repeatMode = resolved
        publish()
        emit(QueueEvent.RepeatModeChanged(resolved))
    }

    fun advance(userInitiated: Boolean): AdvanceResult {
        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            return AdvanceResult(song = currentSong(), reload = true)
        }

        val wasFloating = floatingCurrent != null
        val fromLane = lane
        val fromIndex = indexInLane
        val seedBefore = currentSong()
        val sourceBefore = coldSource

        if (floatingCurrent != null) {
            pushPlayed(floatingCurrent!!)
            floatingCurrent = null
        } else {
            when (fromLane) {
                QueueLane.HOT -> {
                    if (fromIndex in hotQueue.indices) pushPlayed(hotQueue.removeAt(fromIndex))
                }
                QueueLane.COLD -> {
                    if (fromIndex in coldQueue.indices) pushPlayed(coldQueue.removeAt(fromIndex))
                }
            }
        }

        // Restock radio as tracks leave cold (shuffle → max; ordered → next release if under)
        maybeRestockRadio()

        if (wasFloating) return resolveNextFromHeads(seedBefore, sourceBefore)

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

        if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty() && radioSession?.active != true) {
            repopulateCold()
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }

        val seed = seedBefore ?: playedStack.lastOrNull()
        emit(QueueEvent.Exhausted(seed = seed, source = sourceBefore, repeatMode = repeatMode))
        tryAutoPlayRescue(seed, sourceBefore)?.let { return it }

        indexInLane = -1
        publish()
        return AdvanceResult(finished = true)
    }

    private fun resolveNextFromHeads(
        seedBefore: Song?,
        sourceBefore: ColdSource?
    ): AdvanceResult {
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
        if (repeatMode == RepeatMode.COLD && coldOriginal.isNotEmpty() && radioSession?.active != true) {
            repopulateCold()
            lane = QueueLane.COLD
            indexInLane = 0
            publish()
            return AdvanceResult(song = coldQueue[0])
        }
        val seed = seedBefore ?: playedStack.lastOrNull()
        emit(QueueEvent.Exhausted(seed = seed, source = sourceBefore, repeatMode = repeatMode))
        tryAutoPlayRescue(seed, sourceBefore)?.let { return it }
        indexInLane = -1
        publish()
        return AdvanceResult(finished = true)
    }

    fun skipPrevious(
        currentPositionMs: Long,
        forceTrackChange: Boolean = false
    ): AdvanceResult {
        val current = currentSong()
        val canGoPrev = playedStack.isNotEmpty()
        if (!canGoPrev) {
            return AdvanceResult(song = current, seekToStart = true)
        }
        if (!forceTrackChange && currentPositionMs > PREV_RESTART_MS) {
            return AdvanceResult(song = current, seekToStart = true)
        }

        val prev = playedStack.removeAt(playedStack.lastIndex)

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
        const val PREV_RESTART_MS = 3_000L
        private const val MAX_PLAYED_STACK = 200
    }
}
