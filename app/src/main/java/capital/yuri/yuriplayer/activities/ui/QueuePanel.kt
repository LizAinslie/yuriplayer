package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import kotlin.math.roundToInt

@Composable
fun QueuePanel(
    snapshot: QueueSnapshot,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (from: Int, to: Int) -> Unit,
    onMoveCold: (from: Int, to: Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }

    LazyColumn(modifier = modifier, state = listState) {
        item { SectionHeader("Queue · ${snapshot.hotQueue.size}") }
        if (snapshot.hotQueue.isEmpty()) {
            item {
                Text(
                    "Swipe right on a song in the library to add it here. Long-press & drag to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        itemsIndexed(
            snapshot.hotQueue,
            key = { i, s -> "hot-${s.id}-$i-${s.path}" }
        ) { index, song ->
            val isCurrent = snapshot.lane == QueueLane.HOT && snapshot.indexInLane == index
            DraggableQueueRow(
                song = song,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                listSize = snapshot.hotQueue.size,
                onClick = { onPlayItem(QueueLane.HOT, index) },
                onMove = { from, to -> onMoveHot(from, to) },
                index = index
            )
        }

        item {
            val contextLabel = snapshot.coldQueue.firstOrNull()?.album
                ?.takeIf { it.isNotBlank() }
                ?: "Playing from"
            SectionHeader(
                "$contextLabel · ${snapshot.coldQueue.size}" +
                    if (snapshot.shuffleEnabled) " · shuffled" else ""
            )
        }
        if (snapshot.coldQueue.isEmpty()) {
            item {
                Text(
                    "Play an album or list to set what’s next after the queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        itemsIndexed(
            snapshot.coldQueue,
            key = { i, s -> "cold-${s.id}-$i-${s.path}" }
        ) { index, song ->
            val isCurrent = snapshot.lane == QueueLane.COLD && snapshot.indexInLane == index
            DraggableQueueRow(
                song = song,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                listSize = snapshot.coldQueue.size,
                onClick = { onPlayItem(QueueLane.COLD, index) },
                onMove = { from, to -> onMoveCold(from, to) },
                index = index
            )
        }
    }
}

@Composable
private fun DraggableQueueRow(
    song: Song,
    isCurrent: Boolean,
    index: Int,
    listSize: Int,
    rowHeightPx: Float,
    onClick: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var fromIndex by remember { mutableIntStateOf(index) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 8f else 0f
                alpha = if (dragging) 0.95f else 1f
            }
            .offset { IntOffset(0, 0) }
            .clickable(enabled = !dragging, onClick = onClick)
            .pointerInput(index, listSize) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        fromIndex = index
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        val deltaRows = (dragOffset / rowHeightPx).roundToInt()
                        val to = (fromIndex + deltaRows).coerceIn(0, listSize - 1)
                        if (to != fromIndex) onMove(fromIndex, to)
                        dragging = false
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragOffset = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount.y
                    }
                )
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(end = 8.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    if (isCurrent) append("▶ ")
                    append(song.displayTitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp, end = 4.dp)
    )
}
