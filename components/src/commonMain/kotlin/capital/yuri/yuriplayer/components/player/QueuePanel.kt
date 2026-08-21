package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.components.model.TrackRowModel
import kotlin.math.roundToInt

private enum class QueueTab { Queue, History }

/**
 * Shared queue chrome used by the mobile overlay and the desktop right rail.
 * Long-press a row and drag to reorder when [onMove] is set.
 */
@Composable
fun QueuePanel(
    nowPlaying: CoverRef?,
    upcoming: List<TrackRowModel>,
    history: List<TrackRowModel>,
    onPlay: (TrackRowModel) -> Unit,
    onClearQueue: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onMove: ((from: Int, to: Int) -> Unit)? = null,
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
                    val density = LocalDensity.current
                    val rowPx = with(density) { 64.dp.toPx() }
                    Box(Modifier.weight(1f)) {
                        LazyColumn(
                            modifier = if (onMove == null) Modifier.fillMaxSize()
                            else Modifier
                                .fillMaxSize()
                                .pointerInput(upcoming, onMove) {
                                    var from: Int? = null
                                    var dy = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            from = (offset.y / rowPx).toInt().coerceIn(0, upcoming.lastIndex)
                                            dy = 0f
                                        },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            dy += drag.y
                                        },
                                        onDragEnd = {
                                            val start = from ?: return@detectDragGesturesAfterLongPress
                                            val to = (start + (dy / rowPx).roundToInt())
                                                .coerceIn(0, upcoming.lastIndex)
                                            if (start != to) onMove(start, to)
                                            from = null
                                        },
                                        onDragCancel = { from = null }
                                    )
                                }
                        ) {
                            itemsIndexed(upcoming, key = { _, row -> row.id }) { _, row ->
                                TrackRow(
                                    track = row,
                                    onClick = { onPlay(row) },
                                    showCover = true,
                                    showAlbum = false
                                )
                            }
                        }
                    }
                    if (onMove != null) {
                        Text(
                            "Long-press and drag to reorder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
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
                        itemsIndexed(history, key = { _, row -> row.id }) { _, row ->
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        capital.yuri.yuriplayer.components.art.CoverArt(
            model = track.artworkUri,
            size = 48.dp,
            corner = 8.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Now playing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                track.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}