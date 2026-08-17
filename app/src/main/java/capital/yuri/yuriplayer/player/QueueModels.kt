package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioSession
import kotlinx.serialization.Serializable

@Serializable
enum class RepeatMode {
    OFF,
    ONE,
    /** Loop the cold queue (album / playlist source) after it ends. */
    COLD
}

@Serializable
enum class QueueLane {
    HOT,
    COLD
}

@Serializable
enum class ColdSourceType {
    ALBUM,
    PLAYLIST,
    ARTIST,
    SONGS,
    /** Named radio session segment (still backs onto an album/release). */
    RADIO,
    UNKNOWN
}

@Serializable
data class ColdSource(
    val type: ColdSourceType,
    val id: String,
    val title: String? = null
) {
    fun matches(type: ColdSourceType, id: String): Boolean =
        this.type == type && this.id.equals(id, ignoreCase = true)
}

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
    val radioUpcoming: List<Song> = emptyList()
) {
    val currentSong: Song?
        get() = when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }

    val flatQueue: List<Song>
        get() = hotQueue + coldQueue

    val isRadio: Boolean
        get() = radioSession?.active == true

    fun isPlayingFromAlbum(albumKey: String): Boolean =
        coldSource?.matches(ColdSourceType.ALBUM, albumKey) == true ||
            (coldSource?.type == ColdSourceType.RADIO &&
                coldSource.id.equals(albumKey, ignoreCase = true))

    fun isPlayingFromPlaylist(playlistId: String): Boolean =
        coldSource?.matches(ColdSourceType.PLAYLIST, playlistId) == true
}
