package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.core.library.sampleCoverArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared album-page atmosphere: page chrome stays [pageBackground], art color
 * blooms radially from the cover (top-center on phone, left column on wide).
 * Mobile and desktop both call this so the wash is identical.
 */
@Composable
fun AlbumArtBackdrop(
    wash: Color,
    accent: Color = wash,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.drawBehind {
            drawAlbumArtScrim(wash = wash, accent = accent)
        },
        content = content
    )
}

fun DrawScope.drawAlbumArtScrim(wash: Color, accent: Color) {
    if (wash.alpha <= 0.01f) return
    val compact = size.width < 600.dp.toPx()
    val center = if (compact) {
        Offset(size.width * 0.5f, size.height * 0.18f)
    } else {
        Offset(size.width * 0.16f, size.height * 0.28f)
    }
    val radius = size.maxDimension * 1.35f
    drawRect(wash.copy(alpha = 0.07f))
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to wash.copy(alpha = 0.50f),
                0.16f to wash.copy(alpha = 0.34f),
                0.34f to wash.copy(alpha = 0.18f),
                0.52f to wash.copy(alpha = 0.09f),
                0.72f to wash.copy(alpha = 0.035f),
                1.00f to Color.Transparent
            ),
            center = center,
            radius = radius
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to accent.copy(alpha = 0.14f),
                0.28f to accent.copy(alpha = 0.06f),
                0.62f to accent.copy(alpha = 0.02f),
                1.00f to Color.Transparent
            ),
            center = center,
            radius = radius * 0.55f
        )
    )
}

@Composable
fun rememberCoverColors(
    artworkUri: String?,
    audioPath: String? = null
): State<PlayerColors?> = produceState(initialValue = null, artworkUri, audioPath) {
    value = withContext(Dispatchers.Default) {
        sampleCoverArgb(artworkUri, audioPath)?.let { playerColorsFromPixels(it) }
    }
}
