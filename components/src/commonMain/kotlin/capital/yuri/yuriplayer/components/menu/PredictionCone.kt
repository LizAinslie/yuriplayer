package capital.yuri.yuriplayer.components.menu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * Amazon / macOS prediction cone: while a submenu is open, travel inside the
 * triangle (previous pointer → submenu near-edge corners) must not switch the
 * highlighted parent item.
 */
fun pointInTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    if (!finite(p) || !finite(a) || !finite(b) || !finite(c)) return false
    val v0x = c.x - a.x
    val v0y = c.y - a.y
    val v1x = b.x - a.x
    val v1y = b.y - a.y
    val v2x = p.x - a.x
    val v2y = p.y - a.y
    val dot00 = v0x * v0x + v0y * v0y
    val dot01 = v0x * v1x + v0y * v1y
    val dot02 = v0x * v2x + v0y * v2y
    val dot11 = v1x * v1x + v1y * v1y
    val dot12 = v1x * v2x + v1y * v2y
    val denom = dot00 * dot11 - dot01 * dot01
    if (abs(denom) < 1e-6f) return false
    val inv = 1f / denom
    val u = (dot11 * dot02 - dot01 * dot12) * inv
    val v = (dot00 * dot12 - dot01 * dot02) * inv
    return u >= -0.02f && v >= -0.02f && u + v <= 1.02f
}

fun predictionCone(origin: Offset, submenu: Rect, openLeft: Boolean = false): Triple<Offset, Offset, Offset>? {
    if (!finite(origin) || submenu.isEmpty) return null
    val edge = if (openLeft) submenu.right else submenu.left
    return Triple(
        origin,
        Offset(edge, submenu.top),
        Offset(edge, submenu.bottom)
    )
}

fun pointerInPredictionCone(current: Offset, origin: Offset, submenu: Rect, openLeft: Boolean = false): Boolean {
    val cone = predictionCone(origin, submenu, openLeft) ?: return false
    return pointInTriangle(current, cone.first, cone.second, cone.third)
}

private fun finite(o: Offset): Boolean = o.x.isFinite() && o.y.isFinite()
