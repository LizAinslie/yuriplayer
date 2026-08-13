package capital.yuri.yuriplayer.activities.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.HistoryEntry
import capital.yuri.yuriplayer.player.PlaybackHistoryStore
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class QueueTab { Queue, History }

private class SectionDragState {
    var from by mutableIntStateOf(-1)
    var hover by mutableIntStateOf(-1)
    var offsetY by mutableFloatStateOf(0f)
    val active: Boolean get() = from >= 0

    fun start(index: Int) {
        from = index
        hover = index
        offsetY = 0f
    }

    fun drag(deltaY: Float, rowHeight: Float, size: Int) {
        offsetY += deltaY
        val raw = from + (offsetY / rowHeight).roundToInt()
        hover = raw.coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun end(): Pair<Int, Int>? {
        val f = from
        val t = hover
        from = -1
        hover = -1
        offsetY = 0f
        return if (f >= 0 && t >= 0 && f != t) f to t else null
    }

    fun cancel() {
        from = -1
        hover = -1
        offsetY = 0f
    }
}

@Composable
fun QueuePanel(
    snapshot: QueueSnapshot,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (from: Int, to: Int) -> Unit,
    onMoveCold: (from: Int, to: Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit,
    onPlayHistorySong: (Song) -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val historyStore: PlaybackHistoryStore = koinInject()
    val history by historyStore.entries.collectAsState()
    var tab by remember { mutableStateOf(QueueTab.Queue) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                label = { Text("Recently Played") }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (tab == QueueTab.History && history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Clear")
                }
            }
        }

        when (tab) {
            QueueTab.Queue -> QueueTabContent(
                snapshot = snapshot,
                onPlayItem = onPlayItem,
                onMoveHot = onMoveHot,
                onMoveCold = onMoveCold,
                modifier = Modifier.weight(1f)
            )
            QueueTab.History -> HistoryTabContent(
                entries = history,
                maxEntries = historyStore.maxEntries,
                onPlay = onPlayHistorySong,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QueueTabContent(
    snapshot: QueueSnapshot,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (from: Int, to: Int) -> Unit,
    onMoveCold: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    val hotDrag = remember { SectionDragState() }
    val coldDrag = remember { SectionDragState() }

    LazyColumn(modifier = modifier, state = rememberLazyListState()) {
        item { SectionHeader("Queue · ${snapshot.hotQueue.size}") }
        if (snapshot.hotQueue.isEmpty()) {
            item {
                Text(
                    "Swipe right on a song to queue it. Long-press & drag to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        itemsIndexed(
            snapshot.hotQueue,
            key = { _, s -> "hot-${s.id}-${s.path}" }
        ) { index, song ->
            val isCurrent = snapshot.lane == QueueLane.HOT && snapshot.indexInLane == index
            LiveReorderRow(
                song = song,
                index = index,
                listSize = snapshot.hotQueue.size,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                drag = hotDrag,
                onClick = { onPlayItem(QueueLane.HOT, index) },
                onCommitMove = { f, t -> onMoveHot(f, t) }
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
            key = { _, s -> "cold-${s.id}-${s.path}" }
        ) { index, song ->
            val isCurrent = snapshot.lane == QueueLane.COLD && snapshot.indexInLane == index
            LiveReorderRow(
                song = song,
                index = index,
                listSize = snapshot.coldQueue.size,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                drag = coldDrag,
                onClick = { onPlayItem(QueueLane.COLD, index) },
                onCommitMove = { f, t -> onMoveCold(f, t) }
            )
        }
    }
}

@Composable
private fun HistoryTabContent(
    entries: List<HistoryEntry>,
    maxEntries: Int,
    onPlay: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Text(
            "Nothing played yet. Tracks show up here as you listen (up to $maxEntries).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val timeFmt = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    LazyColumn(modifier = modifier) {
        item {
            Text(
                "${entries.size} / $maxEntries",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        items(entries, key = { "${it.song.id}-${it.playedAtMs}-${it.song.path}" }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(entry.song) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = entry.song.displayTitle,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    MarqueeText(
                        text = entry.song.displayArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    timeFmt.format(Date(entry.playedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
private fun LiveReorderRow(
    song: Song,
    index: Int,
    listSize: Int,
    isCurrent: Boolean,
    rowHeightPx: Float,
    drag: SectionDragState,
    onClick: () -> Unit,
    onCommitMove: (from: Int, to: Int) -> Unit
) {
    val isDragged = drag.active && drag.from == index

    val targetShift = when {
        !drag.active || isDragged -> 0f
        drag.from < drag.hover && index in (drag.from + 1)..drag.hover -> -rowHeightPx
        drag.from > drag.hover && index in drag.hover until drag.from -> rowHeightPx
        else -> 0f
    }
    val shift by animateFloatAsState(
        targetValue = if (isDragged) drag.offsetY else targetShift,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.85f),
        label = "rowShift"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 10f else 0f)
            .graphicsLayer {
                translationY = shift
                shadowElevation = if (isDragged) 12f else 0f
                scaleX = if (isDragged) 1.02f else 1f
                scaleY = if (isDragged) 1.02f else 1f
            }
            .then(
                if (isDragged) Modifier
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(enabled = !drag.active, onClick = onClick)
            .pointerInput(index, listSize) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.start(index) },
                    onDragEnd = {
                        drag.end()?.let { (f, t) -> onCommitMove(f, t) }
                    },
                    onDragCancel = { drag.cancel() },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.drag(amount.y, rowHeightPx, listSize)
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
            MarqueeText(
                text = buildString {
                    if (isCurrent) append("▶ ")
                    append(song.displayTitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
            MarqueeText(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
