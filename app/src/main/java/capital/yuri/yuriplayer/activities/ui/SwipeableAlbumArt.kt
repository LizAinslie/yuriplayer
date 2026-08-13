package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Album art with:
 * - horizontal swipe → next/prev (finger-follows; peeks neighbor art)
 * - pull down → dismiss now-playing
 * - programmatic [trigger] for eased button-driven transitions
 */
@Composable
fun SwipeableAlbumArt(
    current: PlayerThemeStore.Theme?,
    next: PlayerThemeStore.Theme?,
    prev: PlayerThemeStore.Theme?,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
    onDismiss: () -> Unit,
    onHorizontalFraction: (Float) -> Unit,
    onDismissFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    val dismissThreshold = with(density) { 140.dp.toPx() }
    val trackThreshold = with(density) { 96.dp.toPx() }

    fun settle() {
        scope.launch {
            val x = offsetX.value
            val y = offsetY.value
            when {
                y > dismissThreshold -> {
                    offsetY.animateTo(with(density) { 600.dp.toPx() }, tween(220))
                    onDismiss()
                    offsetX.snapTo(0f)
                    offsetY.snapTo(0f)
                    onDismissFraction(0f)
                    onHorizontalFraction(0f)
                }
                x < -trackThreshold && next != null -> {
                    offsetX.animateTo(-with(density) { 420.dp.toPx() }, tween(200))
                    onSwipeNext()
                    offsetX.snapTo(0f)
                    offsetY.snapTo(0f)
                    onHorizontalFraction(0f)
                }
                x > trackThreshold && prev != null -> {
                    offsetX.animateTo(with(density) { 420.dp.toPx() }, tween(200))
                    onSwipePrev()
                    offsetX.snapTo(0f)
                    offsetY.snapTo(0f)
                    onHorizontalFraction(0f)
                }
                else -> {
                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                    offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                    onHorizontalFraction(0f)
                    onDismissFraction(0f)
                }
            }
            dragging = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(next, prev) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val nx = offsetX.value + dragAmount.x
                            val ny = (offsetY.value + dragAmount.y).coerceAtLeast(0f)
                            // Lock axis: prefer dominant direction
                            if (abs(nx) > abs(ny)) {
                                offsetX.snapTo(nx)
                                offsetY.snapTo(0f)
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                onHorizontalFraction((nx / width).coerceIn(-1f, 1f))
                                onDismissFraction(0f)
                            } else {
                                offsetY.snapTo(ny)
                                offsetX.snapTo(0f)
                                onDismissFraction((ny / dismissThreshold).coerceIn(0f, 1.5f))
                                onHorizontalFraction(0f)
                            }
                        }
                    }
                )
            }
    ) {
        // Neighbor peek behind
        val hFrac = offsetX.value
        if (hFrac < 0f && next != null) {
            ArtLayer(
                bitmap = next.bitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val w = size.width
                        translationX = w + hFrac
                        alpha = (-hFrac / (w * 0.5f)).coerceIn(0f, 1f)
                    }
            )
        }
        if (hFrac > 0f && prev != null) {
            ArtLayer(
                bitmap = prev.bitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val w = size.width
                        translationX = -w + hFrac
                        alpha = (hFrac / (w * 0.5f)).coerceIn(0f, 1f)
                    }
            )
        }

        ArtLayer(
            bitmap = current?.bitmap,
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt())
                }
                .graphicsLayer {
                    val dismiss = (offsetY.value / dismissThreshold).coerceIn(0f, 1f)
                    scaleX = 1f - dismiss * 0.08f
                    scaleY = 1f - dismiss * 0.08f
                    alpha = 1f - dismiss * 0.35f
                }
        )
    }
}

/** Eased horizontal transition for next/prev buttons. */
suspend fun Animatable<Float, *>.animateTrackSkip(direction: Int, widthPx: Float) {
    // direction: -1 next, +1 prev
    animateTo(direction * widthPx, tween(240))
    snapTo(0f)
}

@Composable
private fun ArtLayer(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxSize(0.35f)
            )
        }
    }
}
