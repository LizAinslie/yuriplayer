package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Edge auto-scroll while a reorder drag is active.
 *
 * Finger Y is tracked by **drag deltas** (not window coordinates), seeded from
 * the dragged item's layoutInfo center so it stays correct under graphicsLayer
 * translation. Call [onDragStart], [onDragDelta], [onDragEnd] from long-press
 * handlers. [onScrolled] receives applied scroll so the caller can bump the
 * drag offset and keep the row under the finger.
 */
class DragAutoScrollController(
    private val edgeZonePx: Float,
    private val maxSpeedPxPerSec: Float,
    private val scrollBy: suspend (Float) -> Unit,
    private val viewportHeightProvider: () -> Float
) {
    var active by mutableStateOf(false)
        private set

    /** Finger Y in the scroll viewport (0 = top). Updated via deltas. */
    var fingerY by mutableFloatStateOf(Float.NaN)
        private set

    var onScrolled: ((Float) -> Unit)? = null

    fun onDragStart(fingerYInViewport: Float) {
        val h = viewportHeightProvider().coerceAtLeast(1f)
        fingerY = fingerYInViewport.coerceIn(0f, h)
        active = true
        pendingDelta = 0f
    }

    fun onDragDelta(deltaY: Float) {
        if (!active) return
        val h = viewportHeightProvider().coerceAtLeast(1f)
        fingerY = (if (fingerY.isNaN()) h / 2f else fingerY) + deltaY
        fingerY = fingerY.coerceIn(0f, h)
    }

    fun onDragEnd() {
        active = false
        fingerY = Float.NaN
        pendingDelta = 0f
    }

    fun scrollSpeedPxPerSec(): Float {
        if (!active || fingerY.isNaN()) return 0f
        val h = viewportHeightProvider()
        if (h <= 1f || edgeZonePx <= 0f) return 0f
        val y = fingerY
        val zone = edgeZonePx.coerceAtMost(h / 2f)
        return when {
            y < zone -> {
                val t = (1f - y / zone).coerceIn(0f, 1f)
                -maxSpeedPxPerSec * (t * t)
            }
            y > h - zone -> {
                val t = ((y - (h - zone)) / zone).coerceIn(0f, 1f)
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
                    pendingDelta += speed * dt
                    // Finger stays at the edge while content scrolls underneath
                    // — keep fingerY pinned in the zone so speed doesn't die.
                }
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

/** Seed finger Y from a visible lazy item's center + current drag translation. */
fun fingerYFromListItem(
    listState: LazyListState,
    layoutIndex: Int,
    dragOffsetY: Float = 0f
): Float {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == layoutIndex }
    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        .coerceAtLeast(1f)
    if (item == null) return viewportH / 2f
    return (item.offset + dragOffsetY + item.size / 2f).coerceIn(0f, viewportH)
}

@Composable
fun rememberListDragAutoScroll(
    listState: LazyListState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 80.dp.toPx() }
    val maxSpeed = with(density) { 240.dp.toPx() }
    val controller = remember(listState) {
        DragAutoScrollController(
            edgeZonePx = edge,
            maxSpeedPxPerSec = maxSpeed,
            scrollBy = { delta -> listState.scrollBy(delta) },
            viewportHeightProvider = {
                val info = listState.layoutInfo
                (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            }
        )
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.active }
            .distinctUntilChanged()
            .collect { active ->
                if (active) controller.runWhileActive()
            }
    }
    return controller
}

@Composable
fun rememberGridDragAutoScroll(
    gridState: LazyGridState
): DragAutoScrollController {
    val density = LocalDensity.current
    val edge = with(density) { 80.dp.toPx() }
    val maxSpeed = with(density) { 240.dp.toPx() }
    val controller = remember(gridState) {
        DragAutoScrollController(
            edgeZonePx = edge,
            maxSpeedPxPerSec = maxSpeed,
            scrollBy = { delta -> gridState.scrollBy(delta) },
            viewportHeightProvider = {
                val info = gridState.layoutInfo
                (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            }
        )
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.active }
            .distinctUntilChanged()
            .collect { active ->
                if (active) controller.runWhileActive()
            }
    }
    return controller
}

/** Unused — kept so old call sites compile if any remain. */
@Suppress("unused")
private var _layoutIndexPlaceholder by mutableIntStateOf(-1)
