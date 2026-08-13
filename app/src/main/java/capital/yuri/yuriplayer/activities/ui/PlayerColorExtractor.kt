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
        val vibrant = palette.getVibrantColor(dominant)
        val darkVibrant = palette.getDarkVibrantColor(dominant)
        val mutedSwatch = palette.getDarkMutedColor(palette.getMutedColor(dominant))

        val accent = Color(vibrant)
        val container = Color(darkVibrant).copy(alpha = 1f)
        val surface = Color(mutedSwatch)

        // Prefer a dark stage so white text stays readable (Spotify-like)
        val stage = if (container.luminance() > 0.35f) {
            container.copy(red = container.red * 0.35f, green = container.green * 0.35f, blue = container.blue * 0.35f)
        } else container

        val onStage = if (stage.luminance() > 0.5f) Color.Black else Color.White
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White

        PlayerColors(
            container = stage,
            onContainer = onStage,
            accent = accent,
            onAccent = onAccent,
            muted = onStage.copy(alpha = 0.55f),
            surface = surface,
            onSurface = onStage
        )
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

/** Build a temporary dark ColorScheme tinted by album art for MaterialTheme. */
fun playerColorScheme(colors: PlayerColors, base: ColorScheme): ColorScheme {
    return darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.container,
        onPrimaryContainer = colors.onContainer,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        background = colors.container,
        onBackground = colors.onContainer,
        surface = colors.container,
        onSurface = colors.onContainer,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.muted,
        outline = colors.muted
    )
}
