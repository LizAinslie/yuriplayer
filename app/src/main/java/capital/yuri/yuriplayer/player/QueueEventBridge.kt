package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Collects [QueueManager.events] and routes auto-play decisions.
 *
 * [MusicService] registers [playSourceHandler] in onCreate so refill still goes
 * through the real player path (rebuffer / notification / persist).
 */
class QueueEventBridge(
    private val queue: QueueManager,
    private val autoPlay: MusicServiceAutoPlay
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set by MusicService; cleared on destroy. */
    @Volatile
    var playSourceHandler: ((songs: List<Song>, source: ColdSource?) -> Unit)? = null

    init {
        scope.launch {
            queue.events.collect { event ->
                when (event) {
                    is QueueEvent.SourceChanged -> {
                        autoPlay.noteSource(event.source)
                    }
                    is QueueEvent.Exhausted -> {
                        handleExhausted(event)
                    }
                    is QueueEvent.RepeatModeChanged,
                    is QueueEvent.ShuffleChanged -> Unit
                }
            }
        }
    }

    private fun handleExhausted(event: QueueEvent.Exhausted) {
        val pick = autoPlay.maybePick(
            seedSong = event.seed,
            finishedSource = event.source,
            repeatMode = event.repeatMode
        ) ?: return
        val handler = playSourceHandler
        if (handler == null) {
            Log.w(TAG, "auto-play pick ready but MusicService not registered yet")
            return
        }
        Log.i(TAG, "auto-play → ${pick.album.displayName}")
        handler(pick.album.songs, pick.source)
    }

    fun close() {
        playSourceHandler = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "YuriPlayer.QueueEvents"
    }
}
