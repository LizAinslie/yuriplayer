package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.Song

/**
 * Compact now-playing strip shared by the main shell and the queue page.
 *
 * @param onOpen called when the strip is tapped (or swiped up if [enableSwipeUp]).
 *               Main UI → open full player; queue page → close queue back to full NP.
 */
@Composable
fun NowPlayingPreview(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enableSwipeUp: Boolean = false,
    tonalElevation: androidx.compose.ui.unit.Dp = 3.dp,
    shadowElevation: androidx.compose.ui.unit.Dp = 6.dp
) {
    Surface(
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enableSwipeUp) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f) onOpen()
                        }
                    }
                } else Modifier
            )
    ) {
        Column {
            PlaybackProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                style = ProgressStyle.LINEAR
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArt(song = song, size = 44.dp, corner = 6.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = song?.displayTitle ?: "Not playing",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    MarqueeText(
                        text = song?.displayArtist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
            }
        }
    }
}

/** @deprecated Use [NowPlayingPreview]. Kept as a thin alias for older call sites. */
@Composable
fun MiniPlayerBar(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onExpand: () -> Unit
) {
    NowPlayingPreview(
        song = song,
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onToggle = onToggle,
        onOpen = onExpand,
        enableSwipeUp = true
    )
}
