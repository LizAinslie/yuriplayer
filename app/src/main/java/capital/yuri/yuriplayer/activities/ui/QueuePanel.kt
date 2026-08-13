package capital.yuri.yuriplayer.activities.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.IntOffset
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
    /** When dragging cold items: true if finger is in the hot section zone. */
    var promoteToHot by mutableStateOf(false)
    val active: Boolean get() = from >= 0

    fun start(index: Int) {
        from = index
        hover = index
        offsetY = 0f
        promoteToHot = false
    }

    fun drag(deltaY: Float, rowHeight: Float, size: Int, allowPromote: Boolean) {
        offsetY += deltaY
        val raw = from + (offsetY / rowHeight).roundToInt()
        if (allowPromote && raw < 0) {
            promoteToHot = true
            hover = 0
        } else {
            promoteToHot = false
            hover = raw.coerceIn(0, (size - 1).coerceAtLeast(0))
        }
    }

    fun end(): Triple<Int, Int, Boolean>? {
        val f = from
        val t = hover
        val promote = promoteToHot
        from = -1
        hover = -1
        offsetY = 0f
        promoteToHot = false
        if (f < 0) return null
        return Triple(f, t, promote)
    }

    fun cancel() {
        from = -1
        hover = -1
        offsetY = 0f
        promoteToHot = false
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
    onMoveColdToHot: (Int) -> Unit = {},
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
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.padding(end = 4.dp))
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
                onRemoveHot = onRemoveHot,
                onRemoveCold = onRemoveCold,
                onMoveColdToHot = onMoveColdToHot,
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
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit,
    onMoveColdToHot: (Int) -> Unit,
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
                    "Swipe right on a library song to add. Swipe left to remove. Long-press & drag to reorder.",
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
            SwipeableQueueRow(
                song = song,
                index = index,
                listSize = snapshot.hotQueue.size,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                drag = hotDrag,
                allowPromoteToHot = false,
                showPromoteHint = false,
                onClick = { onPlayItem(QueueLane.HOT, index) },
                onCommitMove = { f, t -> onMoveHot(f, t) },
                onSwipeRemove = { onRemoveHot(index) },
                onSwipePromote = null
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
            Text(
                "Swipe left to remove · swipe right or drag up into Queue to promote",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
            SwipeableQueueRow(
                song = song,
                index = index,
                listSize = snapshot.coldQueue.size,
                isCurrent = isCurrent,
                rowHeightPx = rowHeightPx,
                drag = coldDrag,
                allowPromoteToHot = true,
                showPromoteHint = coldDrag.active && coldDrag.promoteToHot && coldDrag.from == index,
                onClick = { onPlayItem(QueueLane.COLD, index) },
                onCommitMove = { f, t -> onMoveCold(f, t) },
                onSwipeRemove = { onRemoveCold(index) },
                onSwipePromote = { onMoveColdToHot(index) },
                onDragPromote = { onMoveColdToHot(it) }
            )
        }
    }
}

@Composable
private fun SwipeableQueueRow(
    song: Song,
    index: Int,
    listSize: Int,
    isCurrent: Boolean,
    rowHeightPx: Float,
    drag: SectionDragState,
    allowPromoteToHot: Boolean,
    showPromoteHint: Boolean,
    onClick: () -> Unit,
    onCommitMove: (from: Int, to: Int) -> Unit,
    onSwipeRemove: () -> Unit,
    onSwipePromote: (() -> Unit)?,
    onDragPromote: ((Int) -> Unit)? = null
) {
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 96.dp.toPx() }
    var swipeX by remember { mutableFloatStateOf(0f) }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 10f else 0f)
    ) {
        // Underlay: left = promote, right = remove
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onSwipePromote != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(Icons.Default.PlaylistAdd, "Add to queue", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = shift
                    translationX = swipeX
                    shadowElevation = if (isDragged || showPromoteHint) 12f else 0f
                    scaleX = if (isDragged) 1.02f else 1f
                    scaleY = if (isDragged) 1.02f else 1f
                }
                .then(
                    if (isDragged || showPromoteHint) Modifier
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(
                            if (showPromoteHint) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp)
                        )
                    else Modifier.background(MaterialTheme.colorScheme.surface)
                )
                .clickable(enabled = !drag.active && swipeX == 0f, onClick = onClick)
                .pointerInput(index, listSize) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag.start(index) },
                        onDragEnd = {
                            drag.end()?.let { (f, t, promote) ->
                                if (promote && allowPromoteToHot) onDragPromote?.invoke(f)
                                else if (f != t) onCommitMove(f, t)
                            }
                        },
                        onDragCancel = { drag.cancel() },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.drag(amount.y, rowHeightPx, listSize, allowPromoteToHot)
                        }
                    )
                }
                .pointerInput(index) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                swipeX < -swipeThreshold -> onSwipeRemove()
                                swipeX > swipeThreshold && onSwipePromote != null -> onSwipePromote()
                            }
                            swipeX = 0f
                        },
                        onDragCancel = { swipeX = 0f },
                        onHorizontalDrag = { _, amount ->
                            val max = swipeThreshold * 1.4f
                            val min = if (onSwipePromote != null) -max else -max
                            swipeX = (swipeX + amount).coerceIn(min, max)
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
                        if (showPromoteHint) append("↑ Queue · ")
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
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp, end = 4.dp)
    )
}
