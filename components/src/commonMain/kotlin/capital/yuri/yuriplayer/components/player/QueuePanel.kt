package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.components.model.TrackRowModel

private enum class QueueTab { Queue, History }

/**
 * Shared queue chrome used by the mobile overlay and the desktop right rail.
 * Upcoming list + recently played — same tabs as Android [capital.yuri.yuriplayer.activities.ui.QueuePanel].
 */
@Composable
fun QueuePanel(
    nowPlaying: CoverRef?,
    upcoming: List<TrackRowModel>,
    history: List<TrackRowModel>,
    onPlay: (TrackRowModel) -> Unit,
    onClearQueue: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(QueueTab.Queue) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = tab == QueueTab.Queue,
                onClick = { tab = QueueTab.Queue },
                label = { Text("Queue") }
            )
            FilterChip(
                selected = tab == QueueTab.History,
                onClick = { tab = QueueTab.History },
                label = { Text("Recently played") }
            )
            Spacer(Modifier.weight(1f))
            if (tab == QueueTab.Queue && upcoming.isNotEmpty()) {
                TextButton(onClick = onClearQueue) { Text("Clear") }
            }
            if (tab == QueueTab.History && history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) { Text("Clear") }
            }
        }
        when (tab) {
            QueueTab.Queue -> {
                nowPlaying?.let { NowPlayingQueueCard(it) }
                if (upcoming.isEmpty()) {
                    Text(
                        "Nothing up next.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(upcoming, key = { it.id }) { row ->
                            TrackRow(
                                track = row,
                                onClick = { onPlay(row) },
                                showCover = true,
                                showAlbum = false
                            )
                        }
                    }
                }
            }
            QueueTab.History -> {
                if (history.isEmpty()) {
                    Text(
                        "Play something and it’ll land here.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(history, key = { it.id }) { row ->
                            TrackRow(
                                track = row,
                                onClick = { onPlay(row) },
                                showCover = true,
                                showAlbum = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingQueueCard(track: CoverRef) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Now playing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                track.title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                track.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
