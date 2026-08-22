package capital.yuri.yuriplayer.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.components.model.TrackRowModel
import kotlin.math.roundToInt

private enum class QueueTab { Queue, History }

/**
 * Shared queue chrome (mobile overlay + desktop rail).
 * Hot = user-queued (plays next). Cold = album / playlist / radio rest.
 */
@Composable
fun QueuePanel(
    nowPlaying: CoverRef?,
    hot: List<TrackRowModel>,
    cold: List<TrackRowModel>,
    coldLabel: String = "Up next",
    history: List<TrackRowModel>,
    onPlayHot: (Int) -> Unit,
    onPlayCold: (Int) -> Unit,
    onPlayHistory: (TrackRowModel) -> Unit = {},
    onClearHot: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onMoveHot: ((from: Int, to: Int) -> Unit)? = null,
    onMoveCold: ((from: Int, to: Int) -> Unit)? = null,
    likedIds: Set<String> = emptySet(),
    onToggleLike: (String) -> Unit = {},
    songMenu: (TrackRowModel) -> List<out MenuEntry> = { emptyList() },
    artExpanded: Boolean = false,
    onToggleArt: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(QueueTab.Queue) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = tab == QueueTab.Queue,
                        onClick = { tab = QueueTab.Queue },
                        label = { Text("Queue", maxLines = 1) }
                    )
                }
                item {
                    FilterChip(
                        selected = tab == QueueTab.History,
                        onClick = { tab = QueueTab.History },
                        label = { Text("Recently played", maxLines = 1) }
                    )
                }
            }
            if (tab == QueueTab.Queue && hot.isNotEmpty()) {
                TextButton(onClick = onClearHot) { Text("Clear") }
            }
            if (tab == QueueTab.History && history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) { Text("Clear") }
            }
        }
        when (tab) {
            QueueTab.Queue -> {
                nowPlaying?.let { np ->
                    AnimatedVisibility(
                        visible = artExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        ExpandableCover(
                            artworkUri = np.artworkUri,
                            expanded = true,
                            onToggle = onToggleArt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .aspectRatio(1f)
                        )
                    }
                    NowPlayingQueueCard(
                        track = np,
                        showCover = !artExpanded,
                        onToggleArt = onToggleArt
                    )
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (hot.isNotEmpty()) {
                            item(key = "hdr-hot") {
                                SectionHeader("Queue · ${hot.size}")
                            }
                            queueRows(
                                rows = hot,
                                prefix = "hot",
                                onPlay = onPlayHot,
                                onMove = onMoveHot,
                                likedIds = likedIds,
                                onToggleLike = onToggleLike,
                                songMenu = songMenu
                            )
                        }
                        item(key = "hdr-cold") {
                            SectionHeader("$coldLabel · ${cold.size}")
                            Text(
                                if (hot.isNotEmpty()) {
                                    "Queued tracks play first. Drag to reorder."
                                } else {
                                    "Play an album or list to fill what comes next."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (cold.isEmpty()) {
                            item(key = "cold-empty") {
                                Text(
                                    "Nothing else queued.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                        } else {
                            queueRows(
                                rows = cold,
                                prefix = "cold",
                                onPlay = onPlayCold,
                                onMove = onMoveCold,
                                likedIds = likedIds,
                                onToggleLike = onToggleLike,
                                songMenu = songMenu
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
                        itemsIndexed(history, key = { i, row -> "hist-${row.id}-$i" }) { _, row ->
                            TrackRow(
                                track = row,
                                onClick = { onPlayHistory(row) },
                                showCover = true,
                                showAlbum = true,
                                liked = row.id in likedIds,
                                onToggleLike = { onToggleLike(row.id) },
                                contextItems = songMenu(row)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

private fun LazyListScope.queueRows(
    rows: List<TrackRowModel>,
    prefix: String,
    onPlay: (Int) -> Unit,
    onMove: ((Int, Int) -> Unit)?,
    likedIds: Set<String>,
    onToggleLike: (String) -> Unit,
    songMenu: (TrackRowModel) -> List<out MenuEntry>
) {
    itemsIndexed(rows, key = { i, row -> "$prefix-${row.id}-$i" }) { index, row ->
        val density = LocalDensity.current
        val rowPx = with(density) { 64.dp.toPx() }
        TrackRow(
            track = row,
            onClick = { onPlay(index) },
            showCover = true,
            showAlbum = false,
            liked = row.id in likedIds,
            onToggleLike = { onToggleLike(row.id) },
            contextItems = songMenu(row),
            modifier = if (onMove == null) Modifier
            else Modifier.pointerInput(rows, onMove) {
                var from: Int? = null
                var dy = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        from = index
                        dy = 0f
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        dy += drag.y
                    },
                    onDragEnd = {
                        val start = from ?: return@detectDragGesturesAfterLongPress
                        val to = (start + (dy / rowPx).roundToInt())
                            .coerceIn(0, rows.lastIndex)
                        if (start != to) onMove(start, to)
                        from = null
                    },
                    onDragCancel = { from = null }
                )
            }
        )
    }
}

@Composable
private fun NowPlayingQueueCard(
    track: CoverRef,
    showCover: Boolean,
    onToggleArt: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCover) {
            ExpandableCover(
                artworkUri = track.artworkUri,
                expanded = false,
                onToggle = onToggleArt,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
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

@Composable
private fun ExpandableCover(
    artworkUri: String?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(modifier.clip(MaterialTheme.shapes.medium)) {
        CoverArt(
            model = artworkUri,
            modifier = Modifier.fillMaxSize(),
            corner = if (expanded) 16.dp else 8.dp
        )
        if (onToggle != null) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .align(if (expanded) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(if (expanded) 8.dp else 2.dp)
                    .size(if (expanded) 32.dp else 22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (expanded) "Shrink cover" else "Expand cover",
                    modifier = Modifier.size(if (expanded) 20.dp else 16.dp)
                )
            }
        }
    }
}
