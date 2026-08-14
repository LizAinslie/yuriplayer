package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class QueueTab { Queue, History }

/** Stable Lazy keys that survive list head removals (index alone would break placement anim). */
private data class QueuedSong(
    val key: String,
    val index: Int,
    val song: Song
)

private fun keyedQueue(prefix: String, songs: List<Song>, skip: (Song) -> Boolean): List<QueuedSong> {
    val seen = mutableMapOf<String, Int>()
    val out = ArrayList<QueuedSong>(songs.size)
    songs.forEachIndexed { index, song ->
        if (skip(song)) return@forEachIndexed
        val base = "$prefix-${song.path ?: song.contentUri}"
        val n = seen.getOrDefault(base, 0)
        seen[base] = n + 1
        out += QueuedSong(key = "$base#$n", index = index, song = song)
    }
    return out
}

private class SectionDragState {
    var from by mutableIntStateOf(-1)
    var hover by mutableIntStateOf(-1)
    var offsetY by mutableFloatStateOf(0f)
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
    nowPlaying: Song? = null,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (from: Int, to: Int) -> Unit,
    onMoveCold: (from: Int, to: Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit,
    onMoveColdToHot: (Int) -> Unit = {},
    onClearHotQueue: () -> Unit = {},
    onPlayHistorySong: (Song) -> Unit = {},
    onAddHistoryToQueue: (Song) -> Unit = {},
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
            if (tab == QueueTab.Queue && snapshot.hotQueue.isNotEmpty()) {
                TextButton(onClick = onClearHotQueue) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.padding(end = 4.dp))
                    Text("Clear")
                }
            }
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
                nowPlaying = nowPlaying,
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
                onAddToQueue = onAddHistoryToQueue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTabContent(
    snapshot: QueueSnapshot,
    nowPlaying: Song?,
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

    val currentKey = nowPlaying?.let { it.path ?: it.contentUri.toString() }
    fun isCurrent(song: Song): Boolean {
        val k = song.path ?: song.contentUri.toString()
        return currentKey != null && k == currentKey
    }

    val upcomingHot = remember(snapshot.hotQueue, currentKey) {
        keyedQueue("hot", snapshot.hotQueue, ::isCurrent)
    }
    val upcomingCold = remember(snapshot.coldQueue, currentKey) {
        keyedQueue("cold", snapshot.coldQueue, ::isCurrent)
    }
    val showHotSection = upcomingHot.isNotEmpty()

    val placementSpec = spring<androidx.compose.ui.unit.IntOffset>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = 0.85f
    )
    val fadeSpec = tween<Float>(durationMillis = 200)

    Column(modifier = modifier) {
        AnimatedContent(
            targetState = nowPlaying?.let { it.path ?: it.contentUri.toString() },
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
            },
            label = "nowPlayingCard"
        ) { key ->
            val song = nowPlaying?.takeIf {
                (it.path ?: it.contentUri.toString()) == key
            }
            if (song != null) {
                NowPlayingQueueCard(song = song)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = rememberLazyListState()
        ) {
            if (showHotSection) {
                item(key = "hdr-hot") { SectionHeader("Queue · ${upcomingHot.size}") }
                items(
                    items = upcomingHot,
                    key = { it.key }
                ) { entry ->
                    SwipeableQueueRow(
                        song = entry.song,
                        index = entry.index,
                        listSize = snapshot.hotQueue.size,
                        isCurrent = false,
                        rowHeightPx = rowHeightPx,
                        drag = hotDrag,
                        allowPromoteToHot = false,
                        showPromoteHint = false,
                        onClick = { onPlayItem(QueueLane.HOT, entry.index) },
                        onCommitMove = { f, t -> onMoveHot(f, t) },
                        onSwipeRemove = { onRemoveHot(entry.index) },
                        onSwipePromote = null,
                        modifier = Modifier.animateItem(
                            fadeInSpec = fadeSpec,
                            fadeOutSpec = fadeSpec,
                            placementSpec = placementSpec
                        )
                    )
                }
            }

            item(key = "hdr-cold") {
                val contextLabel = snapshot.coldSource?.title
                    ?: snapshot.coldQueue.firstOrNull()?.album?.takeIf { it.isNotBlank() }
                    ?: "Up next"
                SectionHeader(
                    "$contextLabel · ${upcomingCold.size}" +
                        if (snapshot.shuffleEnabled) " · shuffled" else ""
                )
                if (showHotSection) {
                    Text(
                        "Swipe left to remove · swipe right or drag up into Queue to promote",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            if (upcomingCold.isEmpty()) {
                item(key = "cold-empty") {
                    Text(
                        "Play an album or list to fill what comes next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            items(
                items = upcomingCold,
                key = { it.key }
            ) { entry ->
                SwipeableQueueRow(
                    song = entry.song,
                    index = entry.index,
                    listSize = snapshot.coldQueue.size,
                    isCurrent = false,
                    rowHeightPx = rowHeightPx,
                    drag = coldDrag,
                    allowPromoteToHot = true,
                    showPromoteHint = coldDrag.active && coldDrag.promoteToHot && coldDrag.from == entry.index,
                    onClick = { onPlayItem(QueueLane.COLD, entry.index) },
                    onCommitMove = { f, t -> onMoveCold(f, t) },
                    onSwipeRemove = { onRemoveCold(entry.index) },
                    onSwipePromote = { onMoveColdToHot(entry.index) },
                    onDragPromote = { onMoveColdToHot(it) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = fadeSpec,
                        fadeOutSpec = fadeSpec,
                        placementSpec = placementSpec
                    )
                )
            }
        }
    }
}

@Composable
private fun NowPlayingQueueCard(song: Song) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song = song, size = 48.dp, corner = 6.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                MarqueeText(
                    text = song.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                MarqueeText(
                    text = "${song.displayArtist} · ${song.displayAlbum}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
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
    onDragPromote: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
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

    // Delete / promote underlays only while this row is being *horizontally*
    // swiped — never during vertical reorder drag (otherwise they flash under
    // every moving row).
    val showSwipeUnderlay = abs(swipeX) > 1f && !drag.active

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 10f else 0f)
    ) {
        if (showSwipeUnderlay) {
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
                        onDragStart = {
                            swipeX = 0f // clear any residual swipe before reorder
                            drag.start(index)
                        },
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
                .pointerInput(index, drag.active) {
                    if (drag.active) return@pointerInput
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
                            swipeX = (swipeX + amount).coerceIn(-max, max)
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
    onAddToQueue: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
                "${entries.size} / $maxEntries · swipe right to queue",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        items(entries, key = { "${it.song.id}-${it.playedAtMs}-${it.song.path}" }) { entry ->
            HistorySwipeRow(
                entry = entry,
                timeLabel = timeFmt.format(Date(entry.playedAtMs)),
                onPlay = { onPlay(entry.song) },
                onAddToQueue = {
                    onAddToQueue(entry.song)
                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun HistorySwipeRow(
    entry: HistoryEntry,
    timeLabel: String,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Text(
            "+ Queue",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
        )
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(entry.playedAtMs, entry.song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX > threshold) onAddToQueue()
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(0f, threshold * 1.5f)
                        }
                    )
                }
                .clickable(onClick = onPlay)
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
                timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
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
