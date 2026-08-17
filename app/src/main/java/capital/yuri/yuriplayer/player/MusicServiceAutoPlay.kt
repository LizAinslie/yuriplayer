package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioPick

/**
 * Thin adapter so [QueueManager] keeps a stable auto-play API while the real
 * logic lives in [RadioEngine] (pluggable algorithms, playlist radios, etc.).
 */
class MusicServiceAutoPlay(
    private val engine: RadioEngine
) {

    fun noteSource(source: ColdSource?) {
        engine.noteSource(source)
    }

    /**
     * @return a pick compatible with the old ArtistRadio.Pick shape for
     * [QueueManager.tryAutoPlayRescue].
     */
    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): LegacyPick? {
        val pick: RadioPick = engine.maybePick(seedSong, finishedSource, repeatMode)
            ?: return null
        return LegacyPick(album = pick.album, source = pick.source)
    }

    /** Same shape QueueManager already expects. */
    data class LegacyPick(
        val album: capital.yuri.yuriplayer.data.AlbumItem,
        val source: ColdSource
    )
}
