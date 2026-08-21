package capital.yuri.yuriplayer.components.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.menu.YuriContextMenu
import kotlin.math.roundToInt

typealias ContextAction = MenuEntry.Item

@Composable
fun ContextMenuAnchor(
    items: List<out MenuEntry>,
    modifier: Modifier = Modifier,
    onLongPressOpen: Boolean = true,
    showPredictionCone: Boolean = false,
    content: @Composable BoxScope.(openMenu: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    val openMenu: () -> Unit = {
        offset = DpOffset(12.dp, 8.dp)
        open = true
    }
    Box(
        modifier.pointerInput(items) {
            if (items.isEmpty()) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    if (!event.buttons.isSecondaryPressed) continue
                    val change = event.changes.firstOrNull() ?: continue
                    change.consume()
                    offset = with(density) {
                        DpOffset(change.position.x.toDp(), change.position.y.toDp())
                    }
                    open = true
                }
            }
        }
    ) {
        content(if (onLongPressOpen && items.isNotEmpty()) openMenu else ({ }))
        if (open && items.isNotEmpty()) {
            val px = with(density) {
                IntOffset(offset.x.toPx().roundToInt(), offset.y.toPx().roundToInt())
            }
            Popup(
                alignment = Alignment.TopStart,
                offset = px,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
            ) {
                YuriContextMenu(
                    entries = items,
                    onDismiss = { open = false },
                    showPredictionCone = showPredictionCone
                )
            }
        }
    }
}
