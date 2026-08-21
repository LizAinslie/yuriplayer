package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spotify-ish slide duration for skip (button or fling settle). */
private val SkipSpec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)

/**
 * Album art card with edge-to-edge neighbor peeks.
 *
 * [allowPrevTrackChange] must be false when Previous only restarts the current
 * track (position > ~3s). In that case we never promote or slide previous art.
 */
@Composable
fun SwipeableAlbumArt(
    currentSong: Song?,
    nextSong: Song?,
    prevSong: Song?,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
    onRestartCurrent: () -> Unit = onSwipePrev,
    onPromoteNext: () -> Unit,
    onPromotePrev: () -> Unit,
    onDismiss: () -> Unit,
    onHorizontalFraction: (Float) -> Unit,
    onDismissFraction: (Float) -> Unit,
    /** True only when Previous will load a different track (not seek-to-start). */
    allowPrevTrackChange: Boolean = true,
    skipToken: Long = 0L,
    skipDirection: Int = 0,
    /** False when the queue already advanced (auto-next) — only play the art. */
    commitSkip: Boolean = true,
    onSkipConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    horizontalInset: androidx.compose.ui.unit.Dp = 20.dp
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var animatingSkip by remember { mutableIntStateOf(0) }
    var suppressSongAnim by remember { mutableStateOf(false) }

    val dismissThreshold = with(density) { 140.dp.toPx() }
    val trackThreshold = with(density) { 96.dp.toPx() }
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    val currentBmp = rememberHqArt(currentSong)
    val nextBmp = rememberHqArt(nextSong)
    val prevBmp = rememberHqArt(if (allowPrevTrackChange) prevSong else null)

    // Last painted current cover. Kept across song identity changes so we can
    // slide the outgoing art out while the new cover enters from the side.
    var heldOutgoingBmp by remember { mutableStateOf<Bitmap?>(null) }
    var slideOutBmp by remember { mutableStateOf<Bitmap?>(null) }
    var slideInBmp by remember { mutableStateOf<Bitmap?>(null) }
    var lastSongKey by remember { mutableStateOf(currentSong?.songKey) }

    // Only use prev cover when we will actually change tracks (Spotify 3s rule).
    val effectivePrev = if (allowPrevTrackChange) prevSong else null

    suspend fun animateSkipTo(targetX: Float) {
        offsetX.animateTo(targetX, SkipSpec) {
            onHorizontalFraction((value / screenWidthPx).coerceIn(-1f, 1f))
        }
    }

    suspend fun finishNext(commit: Boolean) {
        suppressSongAnim = true
        onPromoteNext()
        onHorizontalFraction(0f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        if (commit) onSwipeNext()
    }

    suspend fun finishPrev(commit: Boolean) {
        suppressSongAnim = true
        onPromotePrev()
        onHorizontalFraction(0f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        if (commit) onSwipePrev()
    }

    /** Restart current track only — no art/theme swap. */
    suspend fun finishRestartOnly() {
        onHorizontalFraction(0f)
        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
        offsetY.snapTo(0f)
        onRestartCurrent()
    }

    fun settle() {
        scope.launch {
            if (animatingSkip != 0) return@launch
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
                x < -trackThreshold && nextSong != null -> {
                    animatingSkip = -1
                    animateSkipTo(-screenWidthPx)
                    finishNext(commit = true)
                    animatingSkip = 0
                }
                x > trackThreshold && effectivePrev != null -> {
                    animatingSkip = 1
                    animateSkipTo(screenWidthPx)
                    finishPrev(commit = true)
                    animatingSkip = 0
                }
                x > trackThreshold && !allowPrevTrackChange -> {
                    // Past threshold but Previous is seek-to-start only.
                    animatingSkip = 1
                    finishRestartOnly()
                    animatingSkip = 0
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

    LaunchedEffect(skipToken) {
        if (skipToken == 0L || skipDirection == 0) return@LaunchedEffect
        if (animatingSkip != 0) {
            onSkipConsumed()
            return@LaunchedEffect
        }
        when {
            skipDirection < 0 && nextSong != null -> {
                animatingSkip = -1
                animateSkipTo(-screenWidthPx)
                finishNext(commit = commitSkip)
                animatingSkip = 0
            }
            skipDirection > 0 && effectivePrev != null -> {
                animatingSkip = 1
                animateSkipTo(screenWidthPx)
                finishPrev(commit = commitSkip)
                animatingSkip = 0
            }
            // Button Previous while still in the "restart current" window.
            skipDirection > 0 -> {
                if (commitSkip) onSwipePrev()
            }
            skipDirection < 0 -> if (commitSkip) onSwipeNext()
        }
        onSkipConsumed()
    }

    val latestPromoteNext = rememberUpdatedState(onPromoteNext)
    val latestPromotePrev = rememberUpdatedState(onPromotePrev)
    val latestSkipDir = rememberUpdatedState(skipDirection)
    val playingSongKey = currentSong?.songKey

    LaunchedEffect(playingSongKey) {
        val previousKey = lastSongKey
        lastSongKey = playingSongKey
        if (previousKey == null || playingSongKey == null || previousKey == playingSongKey) {
            if (animatingSkip == 0) heldOutgoingBmp = currentBmp
            return@LaunchedEffect
        }
        if (suppressSongAnim) {
            suppressSongAnim = false
            heldOutgoingBmp = currentBmp
            return@LaunchedEffect
        }
        if (animatingSkip != 0) {
            heldOutgoingBmp = currentBmp
            return@LaunchedEffect
        }

        val dir = if (latestSkipDir.value > 0) 1 else -1
        slideOutBmp = heldOutgoingBmp ?: currentBmp
        slideInBmp = currentBmp
        animatingSkip = dir
        animateSkipTo(if (dir > 0) screenWidthPx else -screenWidthPx)
        if (dir > 0) latestPromotePrev.value() else latestPromoteNext.value()
        onHorizontalFraction(0f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        slideOutBmp = null
        slideInBmp = null
        animatingSkip = 0
        heldOutgoingBmp = currentBmp
        onSkipConsumed()
    }

    LaunchedEffect(currentBmp, playingSongKey) {
        if (animatingSkip == 0) heldOutgoingBmp = currentBmp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(nextSong, effectivePrev, allowPrevTrackChange, animatingSkip) {
                if (animatingSkip != 0) return@pointerInput
                detectDragGestures(
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            var nx = offsetX.value + dragAmount.x
                            // Rubber-band prev direction when restart-only (no track change).
                            if (!allowPrevTrackChange && nx > 0f) {
                                nx *= 0.35f
                            }
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
        val showNext = hFrac < 0f && (animatingSkip < 0 || (animatingSkip == 0 && nextSong != null))
        val showPrev = hFrac > 0f && (animatingSkip > 0 || (animatingSkip == 0 && effectivePrev != null))
        val incomingNextBmp = if (animatingSkip < 0) slideInBmp else nextBmp
        val incomingPrevBmp = if (animatingSkip > 0) slideInBmp else prevBmp
        val outgoingBmp = if (animatingSkip != 0) slideOutBmp else currentBmp

        if (showNext) {
            ArtCard(
                bitmap = incomingNextBmp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = screenWidthPx + hFrac
                        alpha = (-hFrac / (screenWidthPx * 0.35f)).coerceIn(0f, 1f)
                    }
            )
        }
        // Never paint previous cover while Previous only seeks to start.
        if (showPrev) {
            ArtCard(
                bitmap = incomingPrevBmp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = -screenWidthPx + hFrac
                        alpha = (hFrac / (screenWidthPx * 0.35f)).coerceIn(0f, 1f)
                    }
            )
        }

        ArtCard(
            bitmap = outgoingBmp,
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
private fun rememberHqArt(song: Song?): Bitmap? {
    val context = LocalContext.current
    val artCache: AlbumArtCache = koinInject()
    val key = song?.let { "${it.songKey}\u0000${artCache.artKey(it)}" }
    var bitmap by remember(key) {
        mutableStateOf(if (song != null) artCache.peek(song, AlbumArtCache.HQ_DECODE_SIZE) else null)
    }
    LaunchedEffect(key) {
        bitmap = if (song != null) artCache.get(context, song, AlbumArtCache.HQ_DECODE_SIZE) else null
    }
    return bitmap
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
                filterQuality = FilterQuality.High,
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
