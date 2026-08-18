package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

data class PlayerColors(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val surface: Color,
    val onSurface: Color
)

suspend fun extractPlayerColors(bitmap: Bitmap?, fallback: ColorScheme): PlayerColors =
    withContext(Dispatchers.Default) {
        if (bitmap == null || bitmap.isRecycled) {
            return@withContext fallbackPlayerColors(fallback)
        }
        val palette = try {
            Palette.from(bitmap).maximumColorCount(16).generate()
        } catch (_: Exception) {
            return@withContext fallbackPlayerColors(fallback)
        }

        val dominant = palette.getDominantColor(fallback.primary.toArgb())
        val vibrant = palette.getVibrantColor(
            palette.getLightVibrantColor(
                palette.getMutedColor(dominant)
            )
        )
        val lightVibrant = palette.getLightVibrantColor(vibrant)
        val darkVibrant = palette.getDarkVibrantColor(dominant)
        val mutedSwatch = palette.getDarkMutedColor(palette.getMutedColor(dominant))

        val containerRaw = Color(darkVibrant)
        // Prefer a dark stage so white text stays readable (Spotify-like)
        val stage = if (containerRaw.luminance() > 0.35f) {
            containerRaw.copy(
                red = containerRaw.red * 0.35f,
                green = containerRaw.green * 0.35f,
                blue = containerRaw.blue * 0.35f
            )
        } else containerRaw

        // Accent must pop on the dark stage — lift lightness if palette is muddy
        val accent = ensureAccentOnDark(
            primary = Color(vibrant),
            fallback = Color(lightVibrant),
            stage = stage
        )
        val onStage = if (stage.luminance() > 0.5f) Color.Black else Color.White
        val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White

        PlayerColors(
            container = stage,
            onContainer = onStage,
            accent = accent,
            onAccent = onAccent,
            muted = onStage.copy(alpha = 0.55f),
            surface = Color(mutedSwatch),
            onSurface = onStage
        )
    }

/**
 * Dark album art often yields near-black "vibrant" swatches. Boost so controls
 * stay visible on the dark now-playing stage.
 */
fun ensureAccentOnDark(primary: Color, fallback: Color, stage: Color): Color {
    val candidates = listOf(primary, fallback)
    val usable = candidates.firstOrNull { c ->
        c.luminance() > 0.22f && contrastRatio(c, stage) >= 3.0f
    }
    if (usable != null) return usable

    // Force a readable accent by lifting HSL-ish lightness toward the mid-tones
    val base = if (primary.luminance() >= fallback.luminance()) primary else fallback
    return liftColor(base, minLuminance = 0.42f)
}

private fun liftColor(c: Color, minLuminance: Float): Color {
    if (c.luminance() >= minLuminance) return c
    // Mix toward white until luminance clears the floor
    var t = 0.15f
    var out = c
    while (out.luminance() < minLuminance && t <= 0.85f) {
        out = Color(
            red = c.red + (1f - c.red) * t,
            green = c.green + (1f - c.green) * t,
            blue = c.blue + (1f - c.blue) * t,
            alpha = 1f
        )
        t += 0.1f
    }
    return out
}

private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance() + 0.05f
    val l2 = b.luminance() + 0.05f
    return max(l1, l2) / kotlin.math.min(l1, l2)
}

fun fallbackPlayerColors(scheme: ColorScheme): PlayerColors = PlayerColors(
    container = scheme.surface,
    onContainer = scheme.onSurface,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    muted = scheme.onSurface.copy(alpha = 0.55f),
    surface = scheme.surfaceVariant,
    onSurface = scheme.onSurface
)

/**
 * Build a ColorScheme tinted by album art.
 * [useArtBackground]=false keeps the app default background (Now Playing)
 * while still using art-derived accents.
 */
fun playerColorScheme(
    colors: PlayerColors,
    base: ColorScheme,
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
