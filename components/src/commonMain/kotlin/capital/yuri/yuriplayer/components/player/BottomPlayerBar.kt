package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.formatTime
import capital.yuri.yuriplayer.components.model.CoverRef

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
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (durationMs > 0) {
                Slider(
                    value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    onValueChange = { onSeek((it * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverArt(model = track?.artworkUri, size = 48.dp, corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "Nothing playing",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        track?.subtitle ?: " ",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                IconButton(onClick = onPrev) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
                if (durationMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
