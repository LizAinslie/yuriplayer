package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song

/**
 * UI playback state owned by [MusicService] / [QueueManager], never by a
 * playback engine. Engines only report position and whether audio is flowing.
 */
data class PlayerViewState(
    val song: Song? = null,
    val next: Song? = null,
    val previous: Song? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)
