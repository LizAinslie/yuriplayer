package capital.yuri.yuriplayer.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Observes [QueueManager.events] for side effects that do not own the player.
 * Auto-play *start* is handled in [MusicService] after AdvanceResult.finished
 * so nowPlaying / art / rebuffer stay in sync.
 */
class QueueEventBridge(
    private val queue: QueueManager,
    private val autoPlay: MusicServiceAutoPlay
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            queue.events.collect { event ->
                when (event) {
                    is QueueEvent.SourceChanged -> {
                        autoPlay.noteSource(event.source)
                        Log.d(TAG, "SourceChanged ${event.source}")
                    }
                    is QueueEvent.Exhausted -> {
                        Log.i(
                            TAG,
                            "Exhausted seed=${event.seed?.displayTitle} " +
                                "source=${event.source} repeat=${event.repeatMode} " +
                                "(MusicService starts auto-play if enabled)"
                        )
                    }
                    is QueueEvent.RepeatModeChanged,
                    is QueueEvent.ShuffleChanged -> Unit
                }
            }
        }
    }

    companion object {
        private const val TAG = "YuriPlayer.QueueEvents"
    }
}
