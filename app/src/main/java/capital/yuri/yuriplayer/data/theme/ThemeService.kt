package capital.yuri.yuriplayer.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import capital.yuri.yuriplayer.activities.ui.PlayerColors
import capital.yuri.yuriplayer.activities.ui.fallbackPlayerColors
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Resolves Material-ish themes from **any** image (album art, playlist cover, …).
 *
 * Preference:
 * - Background → darkMuted / muted, forced dark + desaturated
 * - Accent → vibrant / lightVibrant, boosted saturation & mid lightness
 */
class ThemeService(
    private val artCache: AlbumArtCache
) {
    data class ResolvedTheme(
        val key: String,
        val colors: PlayerColors,
        val bitmap: Bitmap?
    )

    private val cache = ConcurrentHashMap<String, PlayerColors>()

    suspend fun themeFromSong(
        context: Context,
        song: Song?,
        base: ColorScheme,
        maxSize: Int = 768
    ): ResolvedTheme {
        if (song == null) {
            return ResolvedTheme("none", fallbackPlayerColors(base), null)
        }
        val key = artCache.artKey(song)
        cache[key]?.let {
            return ResolvedTheme(key, it, artCache.get(context, song, maxSize))
        }
        val bmp = artCache.get(context, song, maxSize)
        val colors = extractFromBitmap(bmp, base)
        cache[key] = colors
        return ResolvedTheme(key, colors, bmp)
    }

    suspend fun themeFromBitmap(
        key: String,
        bitmap: Bitmap?,
        base: ColorScheme
    ): ResolvedTheme = withContext(Dispatchers.Default) {
        cache[key]?.let { return@withContext ResolvedTheme(key, it, bitmap) }
        val colors = extractFromBitmap(bitmap, base)
        cache[key] = colors
        ResolvedTheme(key, colors, bitmap)
    }

    suspend fun themeFromUri(
        context: Context,
        key: String,
        uri: Uri?,
        base: ColorScheme,
        maxSize: Int = 512
    ): ResolvedTheme = withContext(Dispatchers.IO) {
        cache[key]?.let { return@withContext ResolvedTheme(key, it, null) }
        val bmp = uri?.let { loadBitmap(context, it, maxSize) }
        val colors = extractFromBitmap(bmp, base)
        cache[key] = colors
        ResolvedTheme(key, colors, bmp)
    }

    fun peekCached(key: String): PlayerColors? = cache[key]

    fun clearCache() = cache.clear()

    private fun loadBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calcInSampleSize(maxSize)
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calcInSampleSize(maxSize: Int): Int {
        // Conservative default; AlbumArtCache already sizes song art.
        return 1.coerceAtLeast(1)
    }

    companion object {
        fun extractFromBitmap(bitmap: Bitmap?, fallback: ColorScheme): PlayerColors {
            if (bitmap == null || bitmap.isRecycled) return fallbackPlayerColors(fallback)
            val palette = try {
                Palette.from(bitmap).maximumColorCount(24).generate()
            } catch (_: Exception) {
                return fallbackPlayerColors(fallback)
            }

            val dominant = palette.getDominantColor(fallback.primary.toArgb())
            val darkMuted = palette.getDarkMutedColor(
                palette.getMutedColor(palette.getDarkVibrantColor(dominant))
            )
            val muted = palette.getMutedColor(darkMuted)
            val vibrant = palette.getVibrantColor(
                palette.getLightVibrantColor(
                    palette.getDarkVibrantColor(dominant)
                )
            )
            val lightVibrant = palette.getLightVibrantColor(vibrant)

            // Background: prefer darkMuted → muted, then force dark + desat
            val bgSeed = Color(darkMuted).let { c ->
                if (c.luminance() > 0.25f) Color(muted) else c
            }
            val container = toMutedBackground(bgSeed)

            // Accent: vibrant first, punch up
            val accentSeed = Color(vibrant).let { c ->
                if (c.luminance() < 0.15f) Color(lightVibrant) else c
            }
            val accent = ensureAccentContrast(toPunchyAccent(accentSeed), container)

            val onContainer = if (container.luminance() > 0.5f) Color.Black else Color.White
            val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White

            return PlayerColors(
                container = container,
                onContainer = onContainer,
                accent = accent,
                onAccent = onAccent,
                muted = onContainer.copy(alpha = 0.55f),
                surface = toMutedBackground(Color(muted)),
                onSurface = onContainer
            )
        }

        /** Dark + desaturated stage for backgrounds. */
        fun toMutedBackground(c: Color): Color {
            val r = c.red
            val g = c.green
            val b = c.blue
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            // Desaturate ~55%, then darken toward ~0.10–0.16 luminance
            val desatR = r + (gray - r) * 0.55f
            val desatG = g + (gray - g) * 0.55f
            val desatB = b + (gray - b) * 0.55f
            var out = Color(desatR, desatG, desatB)
            if (out.luminance() > 0.18f) {
                val factor = 0.14f / out.luminance().coerceAtLeast(0.01f)
                out = Color(
                    (desatR * factor).coerceIn(0f, 1f),
                    (desatG * factor).coerceIn(0f, 1f),
                    (desatB * factor).coerceIn(0f, 1f)
                )
            }
            if (out.luminance() < 0.06f) {
                out = Color(
                    (out.red + 0.04f).coerceAtMost(1f),
                    (out.green + 0.04f).coerceAtMost(1f),
                    (out.blue + 0.05f).coerceAtMost(1f)
                )
            }
            return out
        }

        /** Higher saturation, mid brightness for controls. */
        fun toPunchyAccent(c: Color): Color {
            val r = c.red
            val g = c.green
            val b = c.blue
            val max = max(r, max(g, b))
            val min = kotlin.math.min(r, kotlin.math.min(g, b))
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            // Push away from gray (boost sat)
            val satBoost = 1.35f
            var nr = (gray + (r - gray) * satBoost).coerceIn(0f, 1f)
            var ng = (gray + (g - gray) * satBoost).coerceIn(0f, 1f)
            var nb = (gray + (b - gray) * satBoost).coerceIn(0f, 1f)
            var out = Color(nr, ng, nb)
            // Lift if too dark
            if (out.luminance() < 0.40f) {
                val t = ((0.48f - out.luminance()) / 0.48f).coerceIn(0.1f, 0.7f)
                out = Color(
                    nr + (1f - nr) * t,
                    ng + (1f - ng) * t,
                    nb + (1f - nb) * t
                )
            }
            // Soften if too bright
            if (out.luminance() > 0.72f) {
                out = Color(out.red * 0.85f, out.green * 0.85f, out.blue * 0.85f)
            }
            return out
        }

        private fun ensureAccentContrast(accent: Color, stage: Color): Color {
            if (contrastRatio(accent, stage) >= 3.0f && accent.luminance() > 0.22f) return accent
            var t = 0.15f
            var out = accent
            while (contrastRatio(out, stage) < 3.0f && t <= 0.9f) {
                out = Color(
                    accent.red + (1f - accent.red) * t,
                    accent.green + (1f - accent.green) * t,
                    accent.blue + (1f - accent.blue) * t
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
    }
}
