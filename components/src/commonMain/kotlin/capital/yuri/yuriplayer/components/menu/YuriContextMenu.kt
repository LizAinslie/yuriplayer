package capital.yuri.yuriplayer.components.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Material 3 menu with nested submenus and a prediction cone so the pointer
 * can travel diagonally into a submenu without highlighting siblings.
 */
@Composable
fun YuriContextMenu(
    entries: List<out MenuEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showPredictionCone: Boolean = false
) {
    var openSubmenu by remember { mutableIntStateOf(-1) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var coneOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var submenuRect by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier.pointerInput(entries) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.lastOrNull() ?: continue
                    val next = change.position
                    if (pointer != Offset.Unspecified) coneOrigin = pointer
                    pointer = next
                }
            }
        }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MenuColumn(
                entries = entries,
                openSubmenu = openSubmenu,
                onHover = { index ->
                    val origin = if (coneOrigin != Offset.Unspecified) coneOrigin else pointer
                    val blocked = openSubmenu >= 0 &&
                        index != openSubmenu &&
                        pointerInPredictionCone(pointer, origin, submenuRect)
                    if (!blocked) openSubmenu = index
                },
                onOpenSubmenu = { openSubmenu = it },
                onDismiss = onDismiss
            )
            val nested = entries.getOrNull(openSubmenu)?.nestedChildren()
            if (!nested.isNullOrEmpty()) {
                MenuColumn(
                    entries = nested,
                    openSubmenu = -1,
                    onHover = {},
                    onOpenSubmenu = {},
                    onDismiss = onDismiss,
                    modifier = Modifier.onGloballyPositioned {
                        submenuRect = it.boundsInParent()
                    }
                )
            }
        }
        if (showPredictionCone && openSubmenu >= 0) {
            val cone = predictionCone(pointer, submenuRect)
            if (cone != null) {
                Canvas(Modifier.matchParentSize()) {
                    val path = Path().apply {
                        moveTo(cone.first.x, cone.first.y)
                        lineTo(cone.second.x, cone.second.y)
                        lineTo(cone.third.x, cone.third.y)
                        close()
                    }
                    drawPath(path, Color(0x402196F3))
                    drawPath(
                        path,
                        Color(0xFF1565C0),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                    )
                    drawCircle(Color(0xFF1565C0), radius = 3.dp.toPx(), center = cone.first)
                    drawCircle(Color(0xFF1565C0), radius = 3.dp.toPx(), center = cone.second)
                    drawCircle(Color(0xFF1565C0), radius = 3.dp.toPx(), center = cone.third)
                }
            }
        }
    }
}

private fun MenuEntry.nestedChildren(): List<MenuEntry>? = when (this) {
    is MenuEntry.Submenu -> children
    is MenuEntry.Item -> alternate.takeIf { it.isNotEmpty() }
    is MenuEntry.Divider -> null
}

@Composable
private fun MenuColumn(
    entries: List<out MenuEntry>,
    openSubmenu: Int,
    onHover: (Int) -> Unit,
    onOpenSubmenu: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = 180.dp, max = 280.dp)
                .padding(vertical = 6.dp)
        ) {
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    is MenuEntry.Divider -> HorizontalDivider(
                        Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                    )
                    is MenuEntry.Item -> MenuItemRow(
                        label = entry.label,
                        shortcut = entry.shortcut,
                        destructive = entry.destructive,
                        enabled = entry.enabled,
                        selected = openSubmenu == index && entry.alternate.isNotEmpty(),
                        hasSubmenu = entry.alternate.isNotEmpty(),
                        onHover = { onHover(-1) },
                        onClick = {
                            if (entry.enabled) {
                                onDismiss()
                                entry.onClick()
                            }
                        },
                        onAltClick = if (entry.alternate.isNotEmpty()) {
                            { onOpenSubmenu(index) }
                        } else null
                    )
                    is MenuEntry.Submenu -> MenuItemRow(
                        label = entry.label,
                        shortcut = null,
                        destructive = false,
                        enabled = true,
                        selected = openSubmenu == index,
                        hasSubmenu = true,
                        onHover = { onHover(index) },
                        onClick = { onHover(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItemRow(
    label: String,
    shortcut: String?,
    destructive: Boolean,
    enabled: Boolean,
    selected: Boolean,
    hasSubmenu: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
    onAltClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = selected || hovered
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .hoverable(interaction)
            .pointerInput(label, onAltClick) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Enter || e.type == PointerEventType.Move) {
                            if (!e.buttons.isSecondaryPressed) onHover()
                        }
                        if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                            val alt = onAltClick
                            if (alt != null) {
                                e.changes.forEach { it.consume() }
                                alt()
                            }
                        }
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f)
        )
        if (shortcut != null) {
            Text(
                shortcut,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        if (hasSubmenu) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
