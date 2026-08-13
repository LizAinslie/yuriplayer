package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Single-line label that marquees when the text is wider than its bounds
 * (Spotify-style). Uses Foundation [basicMarquee] — only animates on overflow.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    iterations: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        color = color,
        style = style,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier = modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = iterations,
                animationMode = androidx.compose.foundation.MarqueeAnimationMode.Immediately,
                repeatDelayMillis = 1_200,
                initialDelayMillis = 800,
                velocity = 28.dp
            )
    )
}
