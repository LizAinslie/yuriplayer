package capital.yuri.yuriplayer.activities.ui

import MarqueeText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.ui.TestTags
import org.koin.compose.koinInject

/**
 * Compact now-playing chip. Background follows the current cover; it sits
 * in the scaffold bottom bar so lists pad above it, visually floating over
 * the tab bar.
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
    tonalElevation: androidx.compose.ui.unit.Dp = 2.dp,
    shadowElevation: androidx.compose.ui.unit.Dp = 8.dp
) {
    val themeStore: PlayerThemeStore = koinInject()
    val ambient = MaterialTheme.colorScheme
    val theme by themeStore.current.collectAsState()
    val peekNext by themeStore.peekNext.collectAsState()
    val peekPrev by themeStore.peekPrev.collectAsState()
    val matched = when {
        song == null -> null
        theme?.songKey == song.songKey -> theme
        peekNext?.songKey == song.songKey -> peekNext
        peekPrev?.songKey == song.songKey -> peekPrev
        else -> null
    }
    val colors = matched?.colors
    val container = colors?.container ?: ambient.surfaceVariant
    val onContainer = colors?.onContainer ?: ambient.onSurface
    val accent = colors?.accent ?: ambient.primary
    val onAccent = colors?.onAccent ?: ambient.onPrimary
    val trackInactive = onContainer.copy(alpha = 0.22f)

    Surface(
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = onContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.MINI_PLAYER)
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
                        color = onContainer,
                        modifier = Modifier.testTag(TestTags.MINI_TITLE)
                    )
                    MarqueeText(
                        text = song?.displayArtist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.65f),
                        modifier = Modifier.testTag(TestTags.MINI_ARTIST)
                    )
                }
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.testTag(TestTags.MINI_PLAY_PAUSE)
                ) {
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
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    NowPlayingPreview(
        song = song,
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        onToggle = onToggle,
        onOpen = onExpand,
        enableSwipeUp = true,
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
    )
}
