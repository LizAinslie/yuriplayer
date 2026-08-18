package capital.yuri.yuriplayer.activities.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Three bouncing bars — Spotify-style “now playing” glyph. */
@Composable
fun PlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 14.dp
) {
    val (h1, h2, h3) = if (animated) {
        val infinite = rememberInfiniteTransition(label = "playing")
        val a by infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(420, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar1"
        )
        val b by infinite.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(360, delayMillis = 90, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar2"
        )
        val c by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(480, delayMillis = 160, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar3"
        )
        Triple(a, b, c)
    } else {
        // Static mid heights while paused
        Triple(0.45f, 0.75f, 0.55f)
    }

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Bar(color, barWidth, maxHeight * h1)
        Bar(color, barWidth, maxHeight * h2)
        Bar(color, barWidth, maxHeight * h3)
    }
}

@Composable
private fun Bar(color: Color, width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height.coerceAtLeast(2.dp))
            .background(color, RoundedCornerShape(1.dp))
    )
}
