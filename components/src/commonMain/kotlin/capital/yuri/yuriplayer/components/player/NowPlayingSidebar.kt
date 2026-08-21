package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        CoverArt(
            model = track?.artworkUri,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(4.dp),
            corner = 20.dp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            track?.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            track?.subtitle ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        QueuePanel(
            nowPlaying = track,
            upcoming = queue.filter { !it.highlighted },
            history = history,
            onPlay = { row ->
                if (history.any { it.id == row.id } && queue.none { it.id == row.id }) onHistoryTrack(row)
                else onQueueTrack(row)
            },
            onClearQueue = onClearQueue,
            modifier = Modifier.weight(1f)
        )
    }
}
