package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.radio.RadioBatch
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioSession

/**
 * Thin adapter: [QueueManager] ↔ [RadioEngine].
 * Exhaust path loads a full [RadioBatch] into cold queue.
 * Restock keeps cold topped up while radio is active.
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
        val batch: RadioBatch = engine.maybePlan(seedSong, finishedSource, repeatMode)
            ?: return null
        return LegacyPick(
            songs = batch.songs,
            source = batch.source,
            session = batch.session,
            upcoming = emptyList()
        )
    }

    /**
     * Top-up songs for the active radio session.
     * If the engine lost its in-memory session (process restore) but the queue
     * still has an active [queueSession], rehydrate first so restock can run.
     */
    fun restock(
        currentColdSize: Int,
        alreadyQueuedKeys: Set<String>,
        queueSession: RadioSession? = null
    ): List<Song> {
        val live = queueSession?.takeIf { it.active }
        if (live != null && engine.session?.active != true) {
            engine.adoptSession(live)
        }
        return engine.restockSongs(currentColdSize, alreadyQueuedKeys)
    }

    fun currentSession(): RadioSession? = engine.session

    fun stopRadio() = engine.stopRadio()

    data class LegacyPick(
        val songs: List<Song>,
        val source: ColdSource,
        val session: RadioSession? = null,
        val upcoming: List<Song> = emptyList()
    )
}
