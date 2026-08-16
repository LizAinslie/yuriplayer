package capital.yuri.yuriplayer.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Collects [QueueManager.events] and routes auto-play decisions through
 * [PlayerController] so the real service path (rebuffer / notification / persist)
 * stays authoritative.
 */
class QueueEventBridge(
    private val queue: QueueManager,
    private val autoPlay: MusicServiceAutoPlay,
    private val player: PlayerController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        Log.i(TAG, "auto-play → ${pick.album.displayName}")
        player.playSource(
            songs = pick.album.songs,
            startIndex = 0,
            source = pick.source
        )
    }

    companion object {
        private const val TAG = "YuriPlayer.QueueEvents"
    }
}
