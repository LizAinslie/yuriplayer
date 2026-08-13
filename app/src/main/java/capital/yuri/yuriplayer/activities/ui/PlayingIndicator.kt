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
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 14.dp
) {
    val infinite = rememberInfiniteTransition(label = "playing")
    val h1 by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val h2 by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, delayMillis = 90, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val h3 by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, delayMillis = 160, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

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
