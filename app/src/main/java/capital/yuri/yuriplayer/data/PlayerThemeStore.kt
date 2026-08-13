package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import capital.yuri.yuriplayer.activities.ui.PlayerColors
import capital.yuri.yuriplayer.activities.ui.fallbackPlayerColors
import capital.yuri.yuriplayer.data.theme.ThemeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.material3.ColorScheme

/**
 * Holds the active now-playing palette + art so reopening the full player
 * does not FOUC. Uses [ThemeService] for muted-bg / punchy-accent extraction.
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
        if (existing != null && existing.artKey == key && existing.songId == song.id) return
        if (existing != null && existing.artKey == key) {
            _current.value = existing.copy(songId = song.id, path = song.path)
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
