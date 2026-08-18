package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import org.koin.compose.koinInject

/**
 * Compact now-playing strip (bottom of library / detail pages).
 *
 * Uses the **app default background** — not album art colors — so it stays
 * neutral across pages. Accents (progress + play) still come from current art.
 * Full Now Playing screen keeps the art-derived background.
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
    edgeToEdgeBottom: Boolean = false,
    tonalElevation: androidx.compose.ui.unit.Dp = 0.dp,
    shadowElevation: androidx.compose.ui.unit.Dp = 4.dp
) {
    val themeStore: PlayerThemeStore = koinInject()
    val theme by themeStore.current.collectAsState()
    val ambient = MaterialTheme.colorScheme
    val accent = theme?.colors?.accent ?: ambient.primary
    val onAccent = theme?.colors?.onAccent ?: ambient.onPrimary
    val trackInactive = ambient.onBackground.copy(alpha = 0.2f)

    Surface(
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        color = ambient.background,
        contentColor = ambient.onBackground,
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
        Column(
            modifier = if (edgeToEdgeBottom) Modifier.navigationBarsPadding() else Modifier
        ) {
            PlaybackProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                style = ProgressStyle.LINEAR,
                activeColor = accent,
                inactiveColor = trackInactive
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(6.dp),
                            ambientColor = accent.copy(alpha = 0.55f),
                            spotColor = accent.copy(alpha = 0.65f)
                        )
                ) {
                    AlbumArt(song = song, size = 44.dp, corner = 6.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = song?.displayTitle ?: "Not playing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ambient.onBackground
                    )
                    MarqueeText(
                        text = song?.displayArtist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ambient.onBackground.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = onToggle) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = onAccent
                        )
                    }
                }
            }
        }
    }
}

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
        enableSwipeUp = true,
        edgeToEdgeBottom = true
    )
}
