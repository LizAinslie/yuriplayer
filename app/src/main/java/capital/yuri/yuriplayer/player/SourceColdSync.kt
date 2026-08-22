package capital.yuri.yuriplayer.player

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the cold queue aligned with its source list.
 *
 * When the active cold source is a playlist, any membership/order change
 * (add, remove, reorder) rebuilds cold via [QueueManager.updateColdFromSource],
 * which resets user reorders on that lane.
 */
class SourceColdSync(
    private val queueManager: QueueManager,
    private val playlists: PlaylistRepository,
    private val scope: CoroutineScope,
    private val onColdUpdated: () -> Unit = {}
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            queueManager.snapshot
                .map { snap ->
                    snap.coldSource
                        ?.takeIf { it.type == ColdSourceType.PLAYLIST }
                        ?.id
                        ?.takeIf { it.isNotBlank() }
                }
                .distinctUntilChanged()
                .collectLatest { playlistId ->
                    if (playlistId == null) return@collectLatest
                    log.i { "watching playlist $playlistId for cold sync" }
                    playlists.observePlaylist(playlistId).collect { pl ->
                        val songs = pl?.songs.orEmpty()
                        if (songs.isEmpty()) return@collect
                        if (queueManager.updateColdFromSource(songs, playlistId)) {
                            onColdUpdated()
                        }
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private val log = yuriLog("ColdSync")
    }
}
