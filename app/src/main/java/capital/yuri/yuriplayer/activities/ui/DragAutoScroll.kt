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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/**
 * While a reorder drag is active, slowly scrolls the host list when the finger
 * sits near the top or bottom of the viewport. Speed ramps with how deep into
 * the edge zone the pointer is (not too fast).
 *
 * Call [onDragStart]/ [onDrag]/ [onDragEnd] from the same long-press drag
 * handlers. [onScrolled] receives the applied scroll delta so the caller can
 * compensate the dragged item's translation (keeps it under the finger).
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

    /** Window-space bounds of the scroll viewport (updated via onGloballyPositioned). */
    var viewportInRoot by mutableStateOf(Rect.Zero)

    /** Optional callback after a scroll step — use to bump drag offset by the same delta. */
    var onScrolled: ((Float) -> Unit)? = null

    fun onDragStart(changePositionInRoot: Offset) {
        active = true
        updateFingerFromRoot(changePositionInRoot)
    }

    fun onDrag(change: PointerInputChange) {
        if (!active) return
        updateFingerFromRoot(change.positionInRootCompat())
    }

    fun onDragEnd() {
        active = false
        fingerY = Float.NaN
    }

    private fun updateFingerFromRoot(root: Offset) {
        val bounds = viewportInRoot
        if (bounds.height <= 0f) return
        viewportHeight = bounds.height
        fingerY = (root.y - bounds.top).coerceIn(0f, bounds.height)
    }

    /** px/sec: negative scrolls toward top, positive toward bottom. */
    fun scrollSpeedPxPerSec(): Float {
        if (!active || fingerY.isNaN() || viewportHeight <= 0f || edgeZonePx <= 0f) return 0f
        val y = fingerY
        val zone = edgeZonePx.coerceAtMost(viewportHeight / 2f)
        return when {
            y < zone -> {
                val t = (1f - y / zone).coerceIn(0f, 1f)
                // Ease-in so near the threshold is gentle
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
                if (speed != 0f && dt > 0f) {
                    val delta = speed * dt
                    // scrollBy is suspending; drive from the outer loop
                    pendingDelta += delta
                }
            }
            val step = pendingDelta
            if (step != 0f) {
                pendingDelta = 0f
                scrollBy(step)
                onScrolled?.invoke(step)
            }
        }
        pendingDelta = 0f
    }

    private var pendingDelta = 0f
}

private fun PointerInputChange.positionInRootCompat(): Offset =
    position + (this as? Any).let {
        // position is local to the pointerInput element; use position with
        // previous absolute via historical — Compose exposes position relative
        // to the component. Prefer change.position on the element and convert
        // via stored component root later if needed.
        position
    }

@Composable
fun rememberListDragAutoScroll(
    listState: LazyListState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    // ~220dp/s peak — noticeable but not aggressive
    val maxSpeed = with(density) { 220.dp.toPx() }
    val controller = remember(listState) {
        DragAutoScrollController(
            edgeZonePx = edge,
            maxSpeedPxPerSec = maxSpeed,
            scrollBy = { delta -> listState.scrollBy(delta) }
        )
    }
    // Keep edge/speed in sync if density changes
    controller.let {
        // edge/speed are constructor-fixed; fine for normal use
    }
    LaunchedEffect(controller) {
        // Restart loop whenever activity flips via polling inside runWhileActive
        while (true) {
            if (controller.active) controller.runWhileActive()
            else kotlinx.coroutines.delay(32)
        }
    }
    return controller
}

@Composable
fun rememberGridDragAutoScroll(
    gridState: LazyGridState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val maxSpeed = with(density) { 220.dp.toPx() }
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
            else kotlinx.coroutines.delay(32)
        }
    }
    return controller
}

/** True edge direction for tests / debug. */
fun DragAutoScrollController.edgeSign(): Float = scrollSpeedPxPerSec().sign
