package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song

/**
 * Discrete queue moments — not continuous UI state (that stays on [QueueManager.snapshot]).
 *
 * Collectors: auto-play, (later) scrobblers, analytics, multi-device sync.
 */
sealed interface QueueEvent {
    /** Cold+hot empty, repeat off / no repopulate. Opportunity for radio / auto-play. */
    data class Exhausted(
        val seed: Song?,
        val source: ColdSource?,
        val repeatMode: RepeatMode
    ) : QueueEvent

    /** A new cold source was loaded via [QueueManager.playSource]. */
    data class SourceChanged(
        val source: ColdSource?,
        val songCount: Int,
        val startSong: Song?
    ) : QueueEvent

    data class RepeatModeChanged(val mode: RepeatMode) : QueueEvent

    data class ShuffleChanged(val enabled: Boolean) : QueueEvent
}
