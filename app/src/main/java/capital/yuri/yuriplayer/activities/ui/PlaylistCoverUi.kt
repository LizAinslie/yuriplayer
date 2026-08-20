package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import capital.yuri.yuriplayer.data.PlaylistRepository
import org.koin.compose.koinInject

/**
 * App-wide request to open the multi-cover picker for a playlist id.
 * Set from MediaActionSheet / My Stuff when the nested detail handler is absent,
 * or from PlaylistDetailScreen changeCover.
 */
object PlaylistCoverUi {
    val openPlaylistId = mutableStateOf<String?>(null)

    fun open(playlistId: String) {
        openPlaylistId.value = playlistId
    }

    fun dismiss() {
        openPlaylistId.value = null
    }
}

/**
 * Mount once near the root (MainActivity content). Renders the multi-cover
 * overlay whenever [PlaylistCoverUi.openPlaylistId] is set.
 */
@Composable
fun PlaylistCoverGlobalHost() {
    val id = PlaylistCoverUi.openPlaylistId.value ?: return
    val repo: PlaylistRepository = koinInject()
    val playlist by repo.observePlaylist(id).collectAsState(initial = null)
    val name = playlist?.name ?: "Playlist"
    PlaylistMultiCoverOverlay(
        playlistId = id,
        playlistName = name,
        open = true,
        onOpenChange = { open ->
            if (!open) PlaylistCoverUi.dismiss()
        }
    )
}
