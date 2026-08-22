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
    hot: List<TrackRowModel>,
    cold: List<TrackRowModel>,
    coldLabel: String = "Up next",
    history: List<TrackRowModel>,
    onPlayHot: (Int) -> Unit,
    onPlayCold: (Int) -> Unit,
    onHistoryTrack: (TrackRowModel) -> Unit,
    onClearHot: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onMoveHot: ((from: Int, to: Int) -> Unit)? = null,
    onMoveCold: ((from: Int, to: Int) -> Unit)? = null,
    likedIds: Set<String> = emptySet(),
    onToggleTrackLike: (String) -> Unit = {},
    songMenu: (TrackRowModel) -> List<out MenuEntry> = { emptyList() },
    nowPlayingMenu: List<out MenuEntry> = emptyList(),
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
            hot = hot,
            cold = cold,
            coldLabel = coldLabel,
            history = history,
            onPlayHot = onPlayHot,
            onPlayCold = onPlayCold,
            onPlayHistory = onHistoryTrack,
            onClearHot = onClearHot,
            onClearHistory = onClearHistory,
            onMoveHot = onMoveHot,
            onMoveCold = onMoveCold,
            likedIds = likedIds,
            onToggleLike = onToggleTrackLike,
            songMenu = songMenu,
            nowPlayingMenu = nowPlayingMenu,
            artExpanded = artExpanded,
            onToggleArt = { artExpanded = !artExpanded },
            modifier = Modifier.weight(1f)
        )
    }
}
