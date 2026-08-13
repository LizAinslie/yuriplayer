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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Album art keeps inset size (rounded card), but horizontal swipe peeks
 * neighbors from the **true screen edge**. Vertical drag dismisses.
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
    modifier: Modifier = Modifier,
    /** Horizontal inset so the card matches the old padded size. */
    horizontalInset: androidx.compose.ui.unit.Dp = 20.dp
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    val dismissThreshold = with(density) { 140.dp.toPx() }
    val trackThreshold = with(density) { 96.dp.toPx() }
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    fun settle() {
        scope.launch {
            val x = offsetX.value
            val y = offsetY.value
            when {
                y > dismissThreshold -> {
                    offsetY.animateTo(with(density) { 640.dp.toPx() }, tween(220))
                    onDismiss()
                    offsetX.snapTo(0f)
                    offsetY.snapTo(0f)
                    onDismissFraction(0f)
                    onHorizontalFraction(0f)
                }
                x < -trackThreshold && next != null -> {
                    offsetX.animateTo(-screenWidthPx, tween(220))
                    onSwipeNext()
                    offsetX.snapTo(0f)
                    offsetY.snapTo(0f)
                    onHorizontalFraction(0f)
                }
                x > trackThreshold && prev != null -> {
                    offsetX.animateTo(screenWidthPx, tween(220))
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
        }
    }

    // Full-width gesture surface; visual card is inset
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(next, prev) {
                detectDragGestures(
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val nx = offsetX.value + dragAmount.x
                            val ny = (offsetY.value + dragAmount.y).coerceAtLeast(0f)
                            if (abs(nx) > abs(ny) * 1.1f || abs(offsetX.value) > 8f) {
                                offsetX.snapTo(nx)
                                offsetY.snapTo(0f)
                                onHorizontalFraction(
                                    (nx / screenWidthPx).coerceIn(-1f, 1f)
                                )
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
        val hFrac = offsetX.value

        // Neighbor cards travel from absolute screen edge
        if (hFrac < 0f && next != null) {
            ArtCard(
                bitmap = next.bitmap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = screenWidthPx + hFrac
                        alpha = (-hFrac / (screenWidthPx * 0.4f)).coerceIn(0f, 1f)
                    }
            )
        }
        if (hFrac > 0f && prev != null) {
            ArtCard(
                bitmap = prev.bitmap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = -screenWidthPx + hFrac
                        alpha = (hFrac / (screenWidthPx * 0.4f)).coerceIn(0f, 1f)
                    }
            )
        }

        ArtCard(
            bitmap = current?.bitmap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalInset)
                .aspectRatio(1f)
                .align(Alignment.Center)
                .offset {
                    IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt())
                }
                .graphicsLayer {
                    val dismiss = (offsetY.value / dismissThreshold).coerceIn(0f, 1f)
                    scaleX = 1f - dismiss * 0.06f
                    scaleY = 1f - dismiss * 0.06f
                    alpha = 1f - dismiss * 0.3f
                }
        )
    }
}

@Composable
private fun ArtCard(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
