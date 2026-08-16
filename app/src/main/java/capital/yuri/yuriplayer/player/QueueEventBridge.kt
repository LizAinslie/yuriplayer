package capital.yuri.yuriplayer.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Secondary path for QueueEvent.Exhausted (logging / future scrobble hooks).
 * Primary auto-play start lives in [MusicService] so the player always rebuffers.
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
                        Log.d(TAG, "SourceChanged ${event.source}")
                    }
                    is QueueEvent.Exhausted -> {
                        // MusicService also handles this; bridge is a safety net if
                        // service-side path missed (e.g. process edge cases).
                        Log.i(
                            TAG,
                            "Exhausted seed=${event.seed?.displayTitle} " +
                                "source=${event.source} repeat=${event.repeatMode}"
                        )
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
        Log.i(TAG, "bridge auto-play → ${pick.album.displayName}")
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
