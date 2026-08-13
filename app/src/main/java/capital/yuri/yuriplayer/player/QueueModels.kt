package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song

enum class RepeatMode {
    OFF,
    ONE,
    /** Loop the cold queue (album / playlist source) after it ends. */
    COLD
}

enum class QueueLane {
    HOT,
    COLD
}

/**
 * Snapshot of the dual-queue system for UI + persistence.
 *
 * Playback order: [hotQueue] (manual, never shuffled) then [coldQueue]
 * (album/playlist; may be shuffled while [coldOriginal] keeps source order).
 */
data class QueueSnapshot(
    val hotQueue: List<Song> = emptyList(),
    val coldQueue: List<Song> = emptyList(),
    val coldOriginal: List<Song> = emptyList(),
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
) {
    val currentSong: Song?
        get() = when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }

    /** Flat list for simple UIs: hot first, then cold. */
    val flatQueue: List<Song>
        get() = hotQueue + coldQueue
}
