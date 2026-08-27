package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioSession
import kotlinx.serialization.Serializable

// Re-export the shared queue types from :core so app code can keep importing
// them from `capital.yuri.yuriplayer.player`. The canonical definitions live in
// `capital.yuri.yuriplayer.core.player`.
typealias RepeatMode = capital.yuri.yuriplayer.core.player.RepeatMode
typealias QueueLane = capital.yuri.yuriplayer.core.player.QueueLane
typealias ColdSourceType = capital.yuri.yuriplayer.core.player.ColdSourceType
typealias ColdSource = capital.yuri.yuriplayer.core.player.ColdSource

/**
 * Snapshot of the dual-queue system for UI + persistence.
 *
 * [radioSession] names the station when radio is active.
 * [radioUpcoming] is the prefetched next radio release (not yet in cold).
 *
 * Shuffle in radio mode only reorders the **current** cold segment; the next
 * radio release is still algorithm-picked (see [RadioSession] KDoc).
 */
@Serializable
data class QueueSnapshot(
    val hotQueue: List<Song> = emptyList(),
    val coldQueue: List<Song> = emptyList(),
    val coldOriginal: List<Song> = emptyList(),
    val coldSource: ColdSource? = null,
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playedStack: List<Song> = emptyList(),
    val radioSession: RadioSession? = null,
    val radioUpcoming: List<Song> = emptyList(),
    /** Playing item when it is not at [indexInLane] (search / one-off). */
    val floatingCurrent: Song? = null
) {
    val currentSong: Song?
        get() = floatingCurrent ?: when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }

    val flatQueue: List<Song>
        get() = hotQueue + coldQueue

    /**
     * True when a radio session is active **or** the cold source is typed RADIO.
     * Label text alone is not enough — some stations set coldSource without
     * keeping [radioSession].active, which hid the tune button.
     */
    val isRadio: Boolean
        get() = radioSession?.active == true || coldSource?.type == ColdSourceType.RADIO

    fun isPlayingFromAlbum(albumKey: String): Boolean =
        coldSource?.matches(ColdSourceType.ALBUM, albumKey) == true ||
            (coldSource?.type == ColdSourceType.RADIO &&
                coldSource.id.equals(albumKey, ignoreCase = true))

    fun isPlayingFromPlaylist(playlistId: String): Boolean =
        coldSource?.matches(ColdSourceType.PLAYLIST, playlistId) == true
}
