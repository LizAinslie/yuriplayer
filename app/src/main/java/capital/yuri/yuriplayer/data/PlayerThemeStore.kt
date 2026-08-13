package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import capital.yuri.yuriplayer.activities.ui.PlayerColors
import capital.yuri.yuriplayer.activities.ui.fallbackPlayerColors
import capital.yuri.yuriplayer.data.theme.ThemeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the active now-playing palette + art so reopening the full player
 * does not FOUC. Uses [ThemeService] for muted-bg / punchy-accent extraction.
 *
 * [promoteNext] / [promotePrev] copy the already-loaded neighbor into current
 * *before* the player advances, so swipe/button animations never flash the
 * outgoing cover or default palette.
 */
class PlayerThemeStore(
    private val artCache: AlbumArtCache,
    private val themeService: ThemeService
) {
    data class Theme(
        val artKey: String,
        val songId: Long,
        val path: String?,
        val colors: PlayerColors,
        val bitmap: Bitmap?
    )

    private val _current = MutableStateFlow<Theme?>(null)
    val current: StateFlow<Theme?> = _current.asStateFlow()

    private val _peekNext = MutableStateFlow<Theme?>(null)
    val peekNext: StateFlow<Theme?> = _peekNext.asStateFlow()

    private val _peekPrev = MutableStateFlow<Theme?>(null)
    val peekPrev: StateFlow<Theme?> = _peekPrev.asStateFlow()

    /** Slide the preloaded next theme into current (swipe / skip-forward). */
    fun promoteNext() {
        val n = _peekNext.value ?: return
        _current.value = n
        // Outgoing becomes previous; next will be filled by updateNeighbors.
        _peekPrev.value = n // temporary; neighbors refresh replaces both
        _peekNext.value = null
    }

    /** Slide the preloaded previous theme into current (swipe / skip-back). */
    fun promotePrev() {
        val p = _peekPrev.value ?: return
        _current.value = p
        _peekNext.value = p
        _peekPrev.value = null
    }

    suspend fun updateCurrent(
        context: Context,
        song: Song?,
        baseScheme: ColorScheme
    ) {
        if (song == null) {
            _current.value = null
            return
        }
        val key = artCache.artKey(song)
        val existing = _current.value
        // Already showing this art (e.g. just promoted) — keep bitmap/colors,
        // only refresh song identity if needed.
        if (existing != null && existing.artKey == key) {
            if (existing.songId != song.id || existing.path != song.path) {
                _current.value = existing.copy(songId = song.id, path = song.path)
            }
            return
        }
        // Prefer already-resolved neighbor with matching art to avoid a flash
        // of fallback colors while themeFromSong runs.
        val fromNext = _peekNext.value?.takeIf { it.artKey == key }
        val fromPrev = _peekPrev.value?.takeIf { it.artKey == key }
        val warm = fromNext ?: fromPrev
        if (warm != null) {
            _current.value = warm.copy(songId = song.id, path = song.path)
            return
        }
        val resolved = themeService.themeFromSong(context, song, baseScheme, maxSize = 768)
        _current.value = Theme(
            artKey = resolved.key,
            songId = song.id,
            path = song.path,
            colors = resolved.colors,
            bitmap = resolved.bitmap
        )
    }

    suspend fun updateNeighbors(
        context: Context,
        next: Song?,
        prev: Song?,
        baseScheme: ColorScheme
    ) {
        artCache.prefetch(context, listOf(next, prev), 512)
        _peekNext.value = next?.let { songToTheme(context, it, baseScheme) }
        _peekPrev.value = prev?.let { songToTheme(context, it, baseScheme) }
    }

    private suspend fun songToTheme(
        context: Context,
        song: Song,
        baseScheme: ColorScheme
    ): Theme {
        val key = artCache.artKey(song)
        val cur = _current.value
        if (cur != null && cur.artKey == key) {
            return Theme(key, song.id, song.path, cur.colors, cur.bitmap)
        }
        val nextT = _peekNext.value
        if (nextT != null && nextT.artKey == key) {
            return Theme(key, song.id, song.path, nextT.colors, nextT.bitmap)
        }
        val prevT = _peekPrev.value
        if (prevT != null && prevT.artKey == key) {
            return Theme(key, song.id, song.path, prevT.colors, prevT.bitmap)
        }
        val resolved = themeService.themeFromSong(context, song, baseScheme, maxSize = 512)
        return Theme(key, song.id, song.path, resolved.colors, resolved.bitmap)
    }

    fun colorsOrFallback(base: ColorScheme): PlayerColors =
        _current.value?.colors ?: fallbackPlayerColors(base)
}
