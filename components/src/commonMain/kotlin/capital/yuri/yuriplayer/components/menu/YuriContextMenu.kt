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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.platform.screenSizePx
import capital.yuri.yuriplayer.components.platform.windowPositionOnScreenPx
import kotlin.math.roundToInt

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

    val density = LocalDensity.current
    // Submenus share the same width bounds as the parent column; include the
    // Surface's 4dp padding on each side to stay consistent with measured sizes.
    val submenuMaxWidthPx = with(density) { 288.dp.toPx() }

    // Viewport width and the popup window's on-screen origin (platform-specific).
    val screenWidthPx = remember { screenSizePx().width }
    val windowOrigin = remember(openSubmenu) {
        if (openSubmenu >= 0) windowPositionOnScreenPx() else IntOffset.Zero
    }

    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var popupPosInWindow by remember { mutableStateOf(IntOffset.Zero) }
    var parentTopInRoot by remember { mutableStateOf(0f) }
    val itemTopsInRoot = remember { mutableStateMapOf<Int, Float>() }

    // Open the submenu to the left when the parent menu would otherwise push it
    // past the right edge of the viewport.
    val flipToLeft = parentSize.width > 0 && screenWidthPx > 0 &&
        (windowOrigin.x + popupPosInWindow.x + parentSize.width + submenuMaxWidthPx.toInt()) > screenWidthPx

    // Vertical offset of the highlighted item relative to the parent menu top.
    val itemY = if (openSubmenu >= 0) {
        itemTopsInRoot[openSubmenu]?.let { (it - parentTopInRoot).roundToInt().coerceAtLeast(0) } ?: 0
    } else 0

    Box(
        modifier
            .pointerInput(entries) {
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
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                popupPosInWindow = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            }
    ) {
        Layout(
            content = {
                MenuColumn(
                    entries = entries,
                    openSubmenu = openSubmenu,
                    onHover = { index ->
                        val origin = if (coneOrigin != Offset.Unspecified) coneOrigin else pointer
                        val blocked = openSubmenu >= 0 &&
                            index != openSubmenu &&
                            pointerInPredictionCone(pointer, origin, submenuRect, flipToLeft)
                        if (!blocked) openSubmenu = index
                    },
                    onOpenSubmenu = { openSubmenu = it },
                    onDismiss = onDismiss,
                    submenuOpensLeft = flipToLeft,
                    onItemPositioned = { index, topInRoot -> itemTopsInRoot[index] = topInRoot },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        parentSize = coords.size
                        parentTopInRoot = coords.positionInRoot().y
                    }
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
        ) { measurables, constraints ->
            val parent = measurables[0].measure(constraints)
            val submenu = measurables.getOrNull(1)?.measure(constraints)
            val submenuWidth = submenu?.width ?: 0
            val submenuHeight = submenu?.height ?: 0

            val width = if (submenu != null) parent.width + submenuWidth else parent.width
            val height = if (submenu != null) maxOf(parent.height, itemY + submenuHeight) else parent.height

            layout(width, height) {
                if (submenu != null && flipToLeft) {
                    submenu.place(0, itemY)
                    parent.place(submenuWidth, 0)
                } else {
                    parent.place(0, 0)
                    submenu?.place(parent.width, itemY)
                }
            }
        }

        if (showPredictionCone && openSubmenu >= 0) {
            val cone = predictionCone(pointer, submenuRect, flipToLeft)
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
    modifier: Modifier = Modifier,
    submenuOpensLeft: Boolean = false,
    onItemPositioned: (Int, Float) -> Unit = { _, _ -> }
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
                        submenuOpensLeft = submenuOpensLeft,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            onItemPositioned(index, coords.positionInRoot().y)
                        },
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
                        submenuOpensLeft = submenuOpensLeft,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            onItemPositioned(index, coords.positionInRoot().y)
                        },
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
    onAltClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    submenuOpensLeft: Boolean = false
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
        modifier
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
                if (submenuOpensLeft) Icons.AutoMirrored.Filled.KeyboardArrowLeft
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
