package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.ui.TestTags
import org.koin.compose.koinInject

/**
 * Slim prev / play / next strip for the queue page.
 * Surface extends under the system gesture bar; controls sit above it.
 */
@Composable
fun QueueTransportBar(
    playing: Boolean,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeStore: PlayerThemeStore = koinInject()
    val theme by themeStore.current.collectAsState()
    val ambient = MaterialTheme.colorScheme
    val accent = theme?.colors?.accent ?: ambient.primary
    val onAccent = theme?.colors?.onAccent ?: ambient.onPrimary

    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        color = ambient.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrev,
                modifier = Modifier.testTag(TestTags.QUEUE_SKIP_PREV)
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    "Previous",
                    modifier = Modifier.size(32.dp),
                    tint = ambient.onSurface
                )
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(56.dp)
                    .background(accent, CircleShape)
                    .testTag(TestTags.QUEUE_PLAY_PAUSE)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                    tint = onAccent
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.testTag(TestTags.QUEUE_SKIP_NEXT)
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    "Next",
                    modifier = Modifier.size(32.dp),
                    tint = ambient.onSurface
                )
            }
        }
    }
}
