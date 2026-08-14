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

enum class ColdSourceType {
    ALBUM,
    PLAYLIST,
    ARTIST,
    SONGS,
    UNKNOWN
}

/**
 * Where the cold queue was initialized from.
 * [id] is stable: albumKey, playlist id, artist key, etc.
 */
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
 * Playback order: [hotQueue] (manual, never shuffled) then [coldQueue]
 * (album/playlist; may be shuffled while [coldOriginal] keeps source order).
 *
 * [playedStack] is the history of consumed tracks (oldest → newest) used by
 * Previous. Persisted so skip-previous still works after process death.
 */
data class QueueSnapshot(
    val hotQueue: List<Song> = emptyList(),
    val coldQueue: List<Song> = emptyList(),
    val coldOriginal: List<Song> = emptyList(),
    val coldSource: ColdSource? = null,
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Consumed tracks, oldest first. Last element is the most recent previous. */
    val playedStack: List<Song> = emptyList()
) {
    val currentSong: Song?
        get() = when (lane) {
            QueueLane.HOT -> hotQueue.getOrNull(indexInLane)
            QueueLane.COLD -> coldQueue.getOrNull(indexInLane)
        }

    val flatQueue: List<Song>
        get() = hotQueue + coldQueue

    fun isPlayingFromAlbum(albumKey: String): Boolean =
        coldSource?.matches(ColdSourceType.ALBUM, albumKey) == true

    fun isPlayingFromPlaylist(playlistId: String): Boolean =
        coldSource?.matches(ColdSourceType.PLAYLIST, playlistId) == true
}
