package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.ContextMenuAnchor
import capital.yuri.yuriplayer.components.list.formatTime
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.core.player.RepeatMode

@Composable
fun BottomPlayerBar(
    track: CoverRef?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    shuffle: Boolean = false,
    repeat: RepeatMode = RepeatMode.OFF,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    volume: Float = 1f,
    onVolume: (Float) -> Unit = {},
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    queueVisible: Boolean = true,
    onToggleQueue: () -> Unit = {},
    songMenu: List<out MenuEntry> = emptyList()
) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .height(84.dp)
        ) {
            ContextMenuAnchor(
                items = if (track != null) songMenu else emptyList(),
                modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 360.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(model = track?.artworkUri, size = 56.dp, corner = 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) {
                        Text(
                            track?.title ?: "Nothing playing",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            track?.subtitle ?: " ",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted
                        )
                    }
                    if (track != null) {
                        IconButton(onClick = onToggleLike) {
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (liked) "Unlike" else "Like",
                                tint = if (liked) accent else muted
                            )
                        }
                    }
                }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.42f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(18.dp),
                            tint = if (shuffle) accent else muted
                        )
                    }
                    IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = onToggle,
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onCycleRepeat, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = when (repeat) {
                                RepeatMode.OFF -> "Repeat off"
                                RepeatMode.COLD -> "Repeat all"
                                RepeatMode.ONE -> "Repeat one"
                            },
                            modifier = Modifier.size(18.dp),
                            tint = if (repeat != RepeatMode.OFF) accent else muted
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                    Spacer(Modifier.width(10.dp))
                    WavySeekBar(
                        progress = if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else 0f,
                        playing = playing,
                        onProgressChange = { if (durationMs > 0) onSeek((it * durationMs).toLong()) },
                        onProgressChangeFinished = { if (durationMs > 0) onSeek((it * durationMs).toLong()) },
                        activeColor = MaterialTheme.colorScheme.onSurface,
                        inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                }
            }
            Row(
                Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleQueue) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        tint = if (queueVisible) accent else muted
                    )
                }
                Icon(Icons.Default.VolumeUp, contentDescription = "Volume", tint = muted)
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.width(100.dp).height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                    )
                )
            }
        }
    }
}
