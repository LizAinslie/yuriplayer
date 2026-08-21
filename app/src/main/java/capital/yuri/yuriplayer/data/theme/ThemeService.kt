package capital.yuri.yuriplayer.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import capital.yuri.yuriplayer.activities.ui.PlayerColors
import capital.yuri.yuriplayer.activities.ui.fallbackPlayerColors
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Resolves Material-ish themes from **any** image (album art, playlist cover, …).
 *
 * Colors are cached in memory + on disk, keyed by art identity + surface
 * (cover vs banner) + the user-selected [ArtColorVariant]. Re-extract only
 * when the artwork or those settings change.
 */
class ThemeService(
    context: Context,
    private val artCache: AlbumArtCache,
    private val settings: LibrarySettings
) {
    data class ResolvedTheme(
        val key: String,
        val colors: PlayerColors,
        val bitmap: Bitmap?
    )

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, PlayerColors>()
    private val disk = appContext.getSharedPreferences(DISK_PREFS, Context.MODE_PRIVATE)
    private val diskLock = Mutex()

    fun colorCacheKey(artKey: String, surface: ArtColorSurface): String {
        val variant = settings.variantFor(surface)
        return "$artKey|${surface.id}|${variant.id}"
    }

    fun peekCached(artKey: String, surface: ArtColorSurface): PlayerColors? {
        val key = colorCacheKey(artKey, surface)
        cache[key]?.let { return it }
        return readDiskUnlocked(key)?.also { cache[key] = it }
    }

    suspend fun themeFromSong(
        context: Context,
        song: Song?,
        base: ColorScheme,
        maxSize: Int = AlbumArtCache.HERO_DECODE_SIZE,
        forceRefresh: Boolean = false,
        surface: ArtColorSurface = ArtColorSurface.COVER,
        loadBitmap: Boolean = true
    ): ResolvedTheme = withContext(Dispatchers.Default) {
        if (song == null) {
            return@withContext ResolvedTheme("none", fallbackPlayerColors(base), null)
        }
        val identity = artCache.artKey(song)
        val key = colorCacheKey(identity, surface)
        if (forceRefresh) {
            cache.remove(key)
            diskLock.withLock { disk.edit().remove(key).apply() }
        } else {
            val hit = cache[key] ?: readDisk(key)?.also { cache[key] = it }
            if (hit != null) {
                val bmp = if (loadBitmap) artCache.get(context, song, maxSize) else null
                return@withContext ResolvedTheme(identity, hit, bmp)
            }
        }
        val bmp = artCache.get(context, song, maxSize)
        if (bmp == null || bmp.isRecycled) {
            return@withContext ResolvedTheme(identity, fallbackPlayerColors(base), null)
        }
        val colors = extractFromBitmap(bmp, base, settings.variantFor(surface))
        cache[key] = colors
        writeDisk(key, colors)
        ResolvedTheme(identity, colors, if (loadBitmap) bmp else null)
    }

    suspend fun themeFromBitmap(
        key: String,
        bitmap: Bitmap?,
        base: ColorScheme,
        surface: ArtColorSurface = ArtColorSurface.COVER
    ): ResolvedTheme = withContext(Dispatchers.Default) {
        val cacheKey = colorCacheKey(key, surface)
        cache[cacheKey]?.let { return@withContext ResolvedTheme(key, it, bitmap) }
        if (bitmap == null || bitmap.isRecycled) {
            return@withContext ResolvedTheme(key, fallbackPlayerColors(base), bitmap)
        }
        val colors = extractFromBitmap(bitmap, base, settings.variantFor(surface))
        cache[cacheKey] = colors
        writeDisk(cacheKey, colors)
        ResolvedTheme(key, colors, bitmap)
    }

    suspend fun themeFromUri(
        context: Context,
        key: String,
        uri: Uri?,
        base: ColorScheme,
        maxSize: Int = AlbumArtCache.HERO_DECODE_SIZE,
        surface: ArtColorSurface = ArtColorSurface.COVER,
        forceRefresh: Boolean = false,
        loadBitmap: Boolean = false
    ): ResolvedTheme = withContext(Dispatchers.IO) {
        val cacheKey = colorCacheKey(key, surface)
        if (forceRefresh) {
            cache.remove(cacheKey)
            diskLock.withLock { disk.edit().remove(cacheKey).apply() }
        } else {
            val hit = cache[cacheKey] ?: readDisk(cacheKey)?.also { cache[cacheKey] = it }
            if (hit != null) {
                val bmp = if (loadBitmap && uri != null) decodeUriBitmap(context, uri, maxSize) else null
                return@withContext ResolvedTheme(key, hit, bmp)
            }
        }
        val bmp = uri?.let { decodeUriBitmap(context, it, maxSize) }
        if (bmp == null || bmp.isRecycled) {
            return@withContext ResolvedTheme(key, fallbackPlayerColors(base), null)
        }
        val colors = extractFromBitmap(bmp, base, settings.variantFor(surface))
        cache[cacheKey] = colors
        writeDisk(cacheKey, colors)
        ResolvedTheme(key, colors, if (loadBitmap) bmp else null)
    }

    fun peekCached(key: String): PlayerColors? = cache[key]

    fun invalidate(key: String) {
        cache.keys.filter { it == key || it.startsWith("$key|") }.forEach { cache.remove(it) }
    }

    fun invalidateAll() = cache.clear()

    fun clearCache() {
        cache.clear()
        disk.edit().clear().apply()
    }

    private suspend fun readDisk(key: String): PlayerColors? = diskLock.withLock {
        readDiskUnlocked(key)
    }

    private fun readDiskUnlocked(key: String): PlayerColors? {
        val packed = disk.getString(key, null) ?: return null
        return unpackColors(packed)
    }

    private suspend fun writeDisk(key: String, colors: PlayerColors) = diskLock.withLock {
        val order = disk.getString(DISK_ORDER, "")
            .orEmpty()
            .split('\n')
            .filter { it.isNotEmpty() }
            .toMutableList()
        order.remove(key)
        order.add(key)
        val editor = disk.edit().putString(key, packColors(colors))
        while (order.size > MAX_DISK) {
            val drop = order.removeAt(0)
            editor.remove(drop)
        }
        editor.putString(DISK_ORDER, order.joinToString("\n")).apply()
    }

    private fun decodeUriBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null
            scaleTo(decoded, maxSize)
        } catch (e: Exception) {
            Log.w(TAG, "uri decode failed $uri", e)
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, maxSize: Int): Int {
        if (w <= 0 || h <= 0 || maxSize <= 0) return 1
        var inSampleSize = 1
        val halfW = w / 2
        val halfH = h / 2
        while (halfW / inSampleSize >= maxSize && halfH / inSampleSize >= maxSize) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaleTo(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSize && h <= maxSize) return src
        val scale = maxSize.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    companion object {
        private const val TAG = "YuriPlayer.Theme"
        private const val DISK_PREFS = "theme_color_cache"
        private const val DISK_ORDER = "__order"
        private const val MAX_DISK = 256

        fun extractFromBitmap(
            bitmap: Bitmap?,
            fallback: ColorScheme,
            variant: ArtColorVariant = ArtColorVariant.AUTO
        ): PlayerColors {
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
            val lightMuted = palette.getLightMutedColor(muted)
            val vibrant = palette.getVibrantColor(
                palette.getLightVibrantColor(
                    palette.getDarkVibrantColor(dominant)
                )
            )
            val lightVibrant = palette.getLightVibrantColor(vibrant)
            val darkVibrant = palette.getDarkVibrantColor(dominant)

            val bgSeed = when (variant) {
                ArtColorVariant.AUTO -> Color(darkMuted).let { c ->
                    if (c.luminance() > 0.25f) Color(muted) else c
                }
                ArtColorVariant.VIBRANT -> Color(darkVibrant).let { c ->
                    if (c.luminance() > 0.35f) Color(vibrant) else c
                }
                ArtColorVariant.MUTED -> Color(muted)
                ArtColorVariant.DARK_MUTED -> Color(darkMuted)
                ArtColorVariant.DOMINANT -> Color(dominant)
            }
            val container = toMutedBackground(bgSeed)

            val accentSeed = when (variant) {
                ArtColorVariant.AUTO -> Color(vibrant).let { c ->
                    if (c.luminance() < 0.15f) Color(lightVibrant) else c
                }
                ArtColorVariant.VIBRANT -> Color(vibrant).let { c ->
                    if (c.luminance() < 0.20f) Color(lightVibrant) else c
                }
                ArtColorVariant.MUTED -> Color(lightMuted).let { c ->
                    if (c.luminance() < 0.20f) Color(muted) else c
                }
                ArtColorVariant.DARK_MUTED -> Color(muted).let { c ->
                    if (c.luminance() < 0.20f) Color(lightMuted) else c
                }
                ArtColorVariant.DOMINANT -> Color(vibrant).let { c ->
                    if (c.luminance() < 0.15f) Color(dominant) else c
                }
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

        fun toMutedBackground(c: Color): Color {
            val r = c.red
            val g = c.green
            val b = c.blue
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
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

        fun toPunchyAccent(c: Color): Color {
            val r = c.red
            val g = c.green
            val b = c.blue
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val satBoost = 1.35f
            var nr = (gray + (r - gray) * satBoost).coerceIn(0f, 1f)
            var ng = (gray + (g - gray) * satBoost).coerceIn(0f, 1f)
            var nb = (gray + (b - gray) * satBoost).coerceIn(0f, 1f)
            var out = Color(nr, ng, nb)
            if (out.luminance() < 0.40f) {
                val t = ((0.48f - out.luminance()) / 0.48f).coerceIn(0.1f, 0.7f)
                out = Color(
                    nr + (1f - nr) * t,
                    ng + (1f - ng) * t,
                    nb + (1f - nb) * t
                )
            }
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

        fun packColors(colors: PlayerColors): String = listOf(
            colors.container,
            colors.onContainer,
            colors.accent,
            colors.onAccent,
            colors.muted,
            colors.surface,
            colors.onSurface
        ).joinToString(",") { c ->
            Integer.toHexString(c.toArgb()).uppercase().padStart(8, '0')
        }

        fun unpackColors(packed: String): PlayerColors? {
            val parts = packed.split(',')
            if (parts.size != 7) return null
            return try {
                fun c(i: Int): Color = Color(parts[i].toLong(16).toInt())
                PlayerColors(
                    container = c(0),
                    onContainer = c(1),
                    accent = c(2),
                    onAccent = c(3),
                    muted = c(4),
                    surface = c(5),
                    onSurface = c(6)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
