package capital.yuri.yuriplayer.activities.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Expressive seek bar: traveling sine wave while [playing], flattens when
 * paused or while the user is dragging the thumb.
 *
 * [onProgressChangeFinished] receives the **exact** drop fraction so callers
 * do not race Compose state or lose the last drag sample.
 */
@Composable
fun WavySeekBar(
    progress: Float,
    playing: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: (fraction: Float) -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    maxWaveAmplitude: Float = 7f,
    waveLength: Float = 32f
) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val isDragging = dragging >= 0f
    val shown = if (isDragging) dragging else progress.coerceIn(0f, 1f)

    val wantWave = playing && !isDragging
    val amplitudeFactor = remember { Animatable(if (wantWave) 1f else 0f) }
    LaunchedEffect(wantWave) {
        amplitudeFactor.animateTo(
            targetValue = if (wantWave) 1f else 0f,
            animationSpec = tween(durationMillis = if (isDragging) 120 else 420)
        )
    }

    val infinite = rememberInfiniteTransition(label = "wavePhase")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val effectivePhase = if (amplitudeFactor.value > 0.01f) phase else 0f

    fun finishAt(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        onProgressChange(f)
        onProgressChangeFinished(f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val p = (offset.x / size.width).coerceIn(0f, 1f)
                    finishAt(p)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val p = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = p
                        onProgressChange(p)
                    },
                    onDragEnd = {
                        val drop = if (dragging >= 0f) dragging else progress
                        dragging = -1f
                        finishAt(drop)
                    },
                    onDragCancel = {
                        dragging = -1f
                        // Cancel: do not seek — report current progress only
                        onProgressChangeFinished(progress.coerceIn(0f, 1f))
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
        val amp = maxWaveAmplitude * density * amplitudeFactor.value
        val len = waveLength * density
        val strokeInactive = 3.5f * density
        val strokeActive = 5.5f * density

        fun waveY(x: Float): Float {
            if (amp < 0.5f) return midY
            return midY + amp * sin(2f * PI.toFloat() * x / len + effectivePhase).toFloat()
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
            style = Stroke(width = strokeInactive, cap = StrokeCap.Round)
        )

        val endX = w * shown
        if (endX > 1f) {
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
                style = Stroke(width = strokeActive, cap = StrokeCap.Round)
            )
        }

        val thumbX = endX.coerceIn(0f, w)
        val thumbY = waveY(thumbX)
        drawCircle(
            color = activeColor.copy(alpha = 0.35f),
            radius = 12f * density,
            center = Offset(thumbX, thumbY)
        )
        drawCircle(
            color = activeColor,
            radius = 8f * density,
            center = Offset(thumbX, thumbY)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.95f),
            radius = 3.5f * density,
            center = Offset(thumbX, thumbY)
        )
    }
}
