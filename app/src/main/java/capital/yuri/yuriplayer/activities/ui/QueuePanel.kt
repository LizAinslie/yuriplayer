package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot

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
    LazyColumn(modifier = modifier) {
        item {
            SectionHeader("Queue · ${snapshot.hotQueue.size}")
        }
        if (snapshot.hotQueue.isEmpty()) {
            item {
                Text(
                    "Swipe right on a song in the library to add it here.",
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
            QueueSongRow(
                song = song,
                isCurrent = isCurrent,
                canMoveUp = index > 0,
                canMoveDown = index < snapshot.hotQueue.lastIndex,
                onClick = { onPlayItem(QueueLane.HOT, index) },
                onMoveUp = { onMoveHot(index, index - 1) },
                onMoveDown = { onMoveHot(index, index + 1) },
                onRemove = { onRemoveHot(index) }
            )
        }

        item {
            val contextLabel = snapshot.coldQueue.firstOrNull()?.displayAlbum
                ?.takeIf { it != "Unknown Album" }
                ?: "Playing from"
            SectionHeader(
                "$contextLabel · ${snapshot.coldQueue.size}" +
                    if (snapshot.shuffleEnabled) " · shuffled" else ""
            )
        }
        if (snapshot.coldQueue.isEmpty()) {
            item {
                Text(
                    "Play an album or list to set what’s playing next after the queue.",
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
            QueueSongRow(
                song = song,
                isCurrent = isCurrent,
                canMoveUp = index > 0,
                canMoveDown = index < snapshot.coldQueue.lastIndex,
                onClick = { onPlayItem(QueueLane.COLD, index) },
                onMoveUp = { onMoveCold(index, index - 1) },
                onMoveDown = { onMoveCold(index, index + 1) },
                onRemove = { onRemoveCold(index) }
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

@Composable
private fun QueueSongRow(
    song: Song,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
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
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove")
        }
    }
}
