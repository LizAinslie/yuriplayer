package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * While a reorder drag is active, slowly scrolls the host list when the finger
 * sits near the top or bottom of the viewport. Speed ramps with depth into the
 * edge zone (gentle near the threshold, faster at the extreme).
 *
 * Wire-up:
 * 1. [viewportInRoot] — set from the scroll container's `onGloballyPositioned`
 * 2. [onDragStart] / [updateFingerRoot] / [onDragEnd] — from long-press handlers
 * 3. [onScrolled] — bump the dragged item's translation by the same delta so it
 *    stays under the finger as the list moves
 */
class DragAutoScrollController(
    private val edgeZonePx: Float,
    private val maxSpeedPxPerSec: Float,
    private val scrollBy: suspend (Float) -> Unit
) {
    var active by mutableStateOf(false)
        private set

    /** Finger Y in the scroll viewport's local coordinates. */
    var fingerY by mutableFloatStateOf(Float.NaN)
        private set

    var viewportHeight by mutableFloatStateOf(0f)
        private set

    /** Window-space bounds of the scroll viewport. */
    var viewportInRoot by mutableStateOf(Rect.Zero)

    /** Applied after each scroll step so callers can compensate drag offset. */
    var onScrolled: ((Float) -> Unit)? = null

    fun onDragStart(fingerRoot: Offset) {
        active = true
        updateFingerRoot(fingerRoot)
    }

    fun updateFingerRoot(fingerRoot: Offset) {
        val bounds = viewportInRoot
        if (bounds.height <= 1f) return
        viewportHeight = bounds.height
        fingerY = (fingerRoot.y - bounds.top).coerceIn(0f, bounds.height)
    }

    fun onDragEnd() {
        active = false
        fingerY = Float.NaN
        pendingDelta = 0f
    }

    /** px/sec: negative → toward top, positive → toward bottom. */
    fun scrollSpeedPxPerSec(): Float {
        if (!active || fingerY.isNaN() || viewportHeight <= 0f || edgeZonePx <= 0f) return 0f
        val y = fingerY
        val zone = edgeZonePx.coerceAtMost(viewportHeight / 2f)
        return when {
            y < zone -> {
                val t = (1f - y / zone).coerceIn(0f, 1f)
                -maxSpeedPxPerSec * (t * t)
            }
            y > viewportHeight - zone -> {
                val t = ((y - (viewportHeight - zone)) / zone).coerceIn(0f, 1f)
                maxSpeedPxPerSec * (t * t)
            }
            else -> 0f
        }
    }

    suspend fun runWhileActive() {
        var last = withFrameNanos { it }
        while (active) {
            withFrameNanos { now ->
                val dt = ((now - last).coerceAtLeast(0L)) / 1_000_000_000f
                last = now
                val speed = scrollSpeedPxPerSec()
                if (speed != 0f && dt > 0f) pendingDelta += speed * dt
            }
            val step = pendingDelta
            if (kotlin.math.abs(step) >= 0.5f) {
                pendingDelta = 0f
                scrollBy(step)
                onScrolled?.invoke(step)
            }
        }
        pendingDelta = 0f
    }

    private var pendingDelta = 0f
}

@Composable
fun rememberListDragAutoScroll(
    listState: LazyListState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    // ~200dp/s peak — slowish, not a fling
    val maxSpeed = with(density) { 200.dp.toPx() }
    val controller = remember(listState) {
        DragAutoScrollController(
            edgeZonePx = edge,
            maxSpeedPxPerSec = maxSpeed,
            scrollBy = { delta -> listState.scrollBy(delta) }
        )
    }
    LaunchedEffect(controller) {
        while (true) {
            if (controller.active) controller.runWhileActive()
            else delay(32)
        }
    }
    return controller
}

@Composable
fun rememberGridDragAutoScroll(
    gridState: LazyGridState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxSpeed = with(density) { 200.dp.toPx() }
    val controller = remember(gridState) {
        DragAutoScrollController(
            edgeZonePx = edge,
            maxSpeedPxPerSec = maxSpeed,
            scrollBy = { delta -> gridState.scrollBy(delta) }
        )
    }
    LaunchedEffect(controller) {
        while (true) {
            if (controller.active) controller.runWhileActive()
            else delay(32)
        }
    }
    return controller
}
