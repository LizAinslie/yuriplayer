package capital.yuri.yuriplayer.components.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

data class PlayerColors(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val surface: Color,
    val onSurface: Color
)

val LocalPlayerColors = staticCompositionLocalOf<PlayerColors?> { null }

fun fallbackPlayerColors(scheme: ColorScheme = YuriDarkColorScheme): PlayerColors = PlayerColors(
    container = scheme.surface,
    onContainer = scheme.onSurface,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    muted = scheme.onSurface.copy(alpha = 0.55f),
    surface = scheme.surfaceVariant,
    onSurface = scheme.onSurface
)

fun playerColorScheme(
    colors: PlayerColors,
    base: ColorScheme = YuriDarkColorScheme,
    useArtBackground: Boolean = true
): ColorScheme {
    val bg = if (useArtBackground) colors.container else base.background
    val onBg = if (useArtBackground) colors.onContainer else base.onBackground
    val surface = if (useArtBackground) colors.container else base.surface
    val onSurface = if (useArtBackground) colors.onContainer else base.onSurface
    return darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.container,
        onPrimaryContainer = colors.onContainer,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        background = bg,
        onBackground = onBg,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = if (useArtBackground) colors.surface else base.surfaceVariant,
        onSurfaceVariant = if (useArtBackground) colors.muted else base.onSurfaceVariant,
        outline = colors.muted
    )
}

/**
 * Palette-from-pixels (no Android Palette). Skips near-black/white, buckets
 * quantized RGB, picks a dark stage + a lifted accent — same rules as mobile.
 */
fun playerColorsFromPixels(argb: IntArray): PlayerColors? {
    if (argb.isEmpty()) return null
    val buckets = HashMap<Int, Bucket>(64)
    for (px in argb) {
        val a = px ushr 24 and 0xFF
        if (a < 32) continue
        val r = px shr 16 and 0xFF
        val g = px shr 8 and 0xFF
        val b = px and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        if (maxC < 18) continue
        if (minC > 245) continue
        val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
        if (sat < 0.08f && maxC < 40) continue
        val key = (r ushr 3 shl 10) or (g ushr 3 shl 5) or (b ushr 3)
        val bucket = buckets.getOrPut(key) { Bucket() }
        bucket.count++
        bucket.r += r
        bucket.g += g
        bucket.b += b
        bucket.sat += sat
    }
    if (buckets.isEmpty()) return null
    val ranked = buckets.values.sortedByDescending { it.count }
    val dominant = ranked.first().color()
    val vibrant = ranked.maxBy { it.sat / it.count * (1f + it.count / ranked.first().count.toFloat()) }.color()

    val containerRaw = dominant
    val stage = if (containerRaw.luminance() > 0.35f) {
        containerRaw.copy(
            red = containerRaw.red * 0.35f,
            green = containerRaw.green * 0.35f,
            blue = containerRaw.blue * 0.35f
        )
    } else containerRaw
    val accent = ensureAccentOnDark(vibrant, dominant, stage)
    val onStage = if (stage.luminance() > 0.5f) Color.Black else Color.White
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
    return PlayerColors(
        container = stage,
        onContainer = onStage,
        accent = accent,
        onAccent = onAccent,
        muted = onStage.copy(alpha = 0.55f),
        surface = Color(
            red = stage.red * 0.85f + 0.08f,
            green = stage.green * 0.85f + 0.08f,
            blue = stage.blue * 0.85f + 0.08f
        ),
        onSurface = onStage
    )
}

private class Bucket {
    var count = 0
    var r = 0
    var g = 0
    var b = 0
    var sat = 0f
    fun color(): Color {
        val n = count.coerceAtLeast(1)
        return Color(r / n / 255f, g / n / 255f, b / n / 255f)
    }
}

private fun ensureAccentOnDark(primary: Color, fallback: Color, stage: Color): Color {
    val usable = listOf(primary, fallback).firstOrNull { c ->
        c.luminance() > 0.22f && contrastRatio(c, stage) >= 3f
    }
    if (usable != null) return usable
    val base = if (primary.luminance() >= fallback.luminance()) primary else fallback
    var t = 0.15f
    var out = base
    while (out.luminance() < 0.42f && t <= 0.85f) {
        out = Color(
            red = base.red + (1f - base.red) * t,
            green = base.green + (1f - base.green) * t,
            blue = base.blue + (1f - base.blue) * t
        )
        t += 0.1f
    }
    return out
}

private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance() + 0.05f
    val l2 = b.luminance() + 0.05f
    return max(l1, l2) / min(l1, l2)
}
