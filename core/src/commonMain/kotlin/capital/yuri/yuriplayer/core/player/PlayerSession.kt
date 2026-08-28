package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.data.Song

/**
 * Serializable snapshot of the playback session for UI + persistence.
 *
 * Supersedes the legacy `PlayerSession` queue host (removed with the desktop
 * `Track` model). Desktop/Android hosts drive the shared [capital.yuri.yuriplayer.player.QueueManager]
 * and materialize this snapshot for their own persistence layer.
 */
data class PlaybackSnapshot(
    val queue: List<Song> = emptyList(),
    val linear: List<Song> = emptyList(),
    val index: Int = 0,
    val history: List<Song> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 1f,
    val positionMs: Long = 0L,
    val hotQueue: List<Song> = emptyList(),
    val coldQueue: List<Song> = emptyList(),
    val coldOriginal: List<Song> = emptyList(),
    val coldSource: ColdSource? = null,
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = 0
)
