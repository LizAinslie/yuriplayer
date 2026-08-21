package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    onClearHistory: () -> Unit = {},
    onMoveUpcoming: ((from: Int, to: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
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
        Spacer(Modifier.height(12.dp))
        if (track != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CoverArt(model = track.artworkUri, size = 48.dp, corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        track.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        track.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        CoverArt(
            model = track?.artworkUri,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(220.dp),
            corner = 16.dp
        )
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
            modifier = Modifier.weight(1f)
        )
    }
}
