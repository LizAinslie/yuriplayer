package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ProgressStyle {
    LINEAR,
    WAVY
}

@Composable
fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    style: ProgressStyle,
    playing: Boolean = false,
    onSeek: ((Long) -> Unit)? = null,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
    modifier: Modifier = Modifier
) {
    val progress = if (durationMs > 0) {
        (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f

    when (style) {
        ProgressStyle.LINEAR -> {
            LinearProgressIndicator(
                progress = { progress },
                modifier = modifier.fillMaxWidth(),
                color = activeColor,
                trackColor = inactiveColor
            )
        }
        ProgressStyle.WAVY -> {
            var slider by remember { mutableFloatStateOf(progress) }
            var sliding by remember { mutableStateOf(false) }

            LaunchedEffect(positionMs, durationMs, sliding) {
                if (!sliding && durationMs > 0) slider = progress
            }

            WavySeekBar(
                progress = slider,
                playing = playing,
                onProgressChange = {
                    sliding = true
                    slider = it
                },
                onProgressChangeFinished = { fraction ->
                    sliding = false
                    if (durationMs > 0 && onSeek != null) {
                        // Double avoids float mantissa error near EOF on long tracks
                        val target = (fraction.toDouble() * durationMs.toDouble())
                            .toLong()
                            .coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L))
                        onSeek(target)
                    }
                },
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
        }
    }
}
