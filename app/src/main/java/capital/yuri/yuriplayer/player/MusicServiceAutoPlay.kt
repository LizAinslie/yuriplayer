package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioSession

/**
 * Thin adapter: [QueueManager] ↔ [RadioEngine].
 */
class MusicServiceAutoPlay(
    private val engine: RadioEngine
) {
    val radioEngine: RadioEngine get() = engine

    fun noteSource(source: ColdSource?) {
        engine.noteSource(source)
    }

    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): LegacyPick? {
        val pick = engine.maybePick(seedSong, finishedSource, repeatMode) ?: return null
        val session = engine.session
        // Brand cold source as radio when a session is active
        val source = if (session?.active == true) {
            ColdSource(
                type = ColdSourceType.RADIO,
                id = pick.source.id,
                title = session.displayName
            )
        } else {
            pick.source
        }
        return LegacyPick(
            album = pick.album,
            source = source,
            session = session,
            upcoming = engine.upcomingSongs()
        )
    }

    fun currentSession(): RadioSession? = engine.session

    fun upcomingSongs(): List<Song> = engine.upcomingSongs()

    fun stopRadio() = engine.stopRadio()

    data class LegacyPick(
        val album: AlbumItem,
        val source: ColdSource,
        val session: RadioSession? = null,
        val upcoming: List<Song> = emptyList()
    )
}
