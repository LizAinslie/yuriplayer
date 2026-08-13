package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Expressive wavy seek bar (Spotify-ish / Material expressive vibes).
 * [progress] is 0f..1f.
 */
@Composable
fun WavySeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    waveAmplitude: Float = 6f,
    waveLength: Float = 28f
) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragging >= 0f) dragging else progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val p = (offset.x / size.width).coerceIn(0f, 1f)
                    onProgressChange(p)
                    onProgressChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(dragging)
                    },
                    onDragEnd = {
                        dragging = -1f
                        onProgressChangeFinished()
                    },
                    onDragCancel = {
                        dragging = -1f
                        onProgressChangeFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        val p = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragging = p
                        onProgressChange(p)
                    }
                )
            }
    ) {
        val w = size.width
        val midY = size.height / 2f
        val amp = waveAmplitude * density
        val len = waveLength * density

        fun waveY(x: Float): Float {
            return midY + amp * sin(2f * PI.toFloat() * x / len).toFloat()
        }

        val inactivePath = Path().apply {
            moveTo(0f, waveY(0f))
            var x = 0f
            while (x <= w) {
                lineTo(x, waveY(x))
                x += 2f
            }
        }
        drawPath(
            path = inactivePath,
            color = inactiveColor,
            style = Stroke(width = 4f * density, cap = StrokeCap.Round)
        )

        val endX = w * shown
        if (endX > 0f) {
            val activePath = Path().apply {
                moveTo(0f, waveY(0f))
                var x = 0f
                while (x <= endX) {
                    lineTo(x, waveY(x))
                    x += 2f
                }
            }
            drawPath(
                path = activePath,
                color = activeColor,
                style = Stroke(width = 5.5f * density, cap = StrokeCap.Round)
            )
        }

        // Thumb
        val thumbX = endX
        val thumbY = waveY(thumbX)
        drawCircle(
            color = activeColor,
            radius = 7f * density,
            center = Offset(thumbX, thumbY)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 3.5f * density,
            center = Offset(thumbX, thumbY)
        )
    }
}
