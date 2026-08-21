package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.components.model.TrackRowModel

@Composable
fun NowPlayingSidebar(
    track: CoverRef?,
    queue: List<TrackRowModel>,
    history: List<TrackRowModel>,
    onQueueTrack: (TrackRowModel) -> Unit,
    onHistoryTrack: (TrackRowModel) -> Unit,
    onClearQueue: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onMoveUpcoming: ((from: Int, to: Int) -> Unit)? = null,
    likedIds: Set<String> = emptySet(),
    onToggleTrackLike: (String) -> Unit = {},
    songMenu: (TrackRowModel) -> List<out MenuEntry> = { emptyList() },
    modifier: Modifier = Modifier
) {
    var artExpanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Text(
            "Queue",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        QueuePanel(
            nowPlaying = track,
            upcoming = queue.dropWhile { it.id != track?.id }.drop(1),
            history = history,
            onPlay = { row ->
                if (history.any { it.id == row.id } && queue.none { it.id == row.id }) onHistoryTrack(row)
                else onQueueTrack(row)
            },
            onClearQueue = onClearQueue,
            onClearHistory = onClearHistory,
            onMove = onMoveUpcoming,
            likedIds = likedIds,
            onToggleLike = onToggleTrackLike,
            songMenu = songMenu,
            artExpanded = artExpanded,
            onToggleArt = { artExpanded = !artExpanded },
            modifier = Modifier.weight(1f)
        )
    }
}
