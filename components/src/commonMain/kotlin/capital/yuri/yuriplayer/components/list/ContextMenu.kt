package capital.yuri.yuriplayer.components.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

data class ContextAction(
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
fun ContextMenuAnchor(
    items: List<ContextAction>,
    modifier: Modifier = Modifier,
    onLongPressOpen: Boolean = true,
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
        if (items.isNotEmpty()) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                offset = offset
            ) {
                items.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                action.label,
                                color = if (action.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        enabled = action.enabled,
                        onClick = {
                            open = false
                            action.onClick()
                        }
                    )
                }
            }
        }
    }
}
