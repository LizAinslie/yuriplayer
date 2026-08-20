package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import capital.yuri.yuriplayer.activities.ui.PlayerColors
import capital.yuri.yuriplayer.activities.ui.fallbackPlayerColors
import capital.yuri.yuriplayer.data.theme.ArtColorSurface
import capital.yuri.yuriplayer.data.theme.ThemeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the active now-playing palette + **HQ** art so reopening the full player
 * does not FOUC. Uses [ThemeService] for muted-bg / punchy-accent extraction.
 */
class PlayerThemeStore(
    private val artCache: AlbumArtCache,
    private val themeService: ThemeService,
    private val settings: LibrarySettings
) {
    data class Theme(
        val artKey: String,
        val songId: Long,
        val path: String?,
        val colors: PlayerColors,
        val bitmap: Bitmap?,
        val colorRev: Long = 0L
    )

    private val _current = MutableStateFlow<Theme?>(null)
    val current: StateFlow<Theme?> = _current.asStateFlow()

    private val _peekNext = MutableStateFlow<Theme?>(null)
    val peekNext: StateFlow<Theme?> = _peekNext.asStateFlow()

    private val _peekPrev = MutableStateFlow<Theme?>(null)
    val peekPrev: StateFlow<Theme?> = _peekPrev.asStateFlow()

    fun promoteNext() {
        val n = _peekNext.value ?: return
        _current.value = n
        _peekPrev.value = n
        _peekNext.value = null
    }

    fun promotePrev() {
        val p = _peekPrev.value ?: return
        _current.value = p
        _peekNext.value = p
        _peekPrev.value = null
    }

    /**
     * @param forceRefresh re-decode art and palette (e.g. after MusicBrainz cover lands).
     */
    suspend fun updateCurrent(
        context: Context,
        song: Song?,
        baseScheme: ColorScheme,
        forceRefresh: Boolean = false
    ) {
        if (song == null) {
            _current.value = null
            return
        }
        val key = artCache.artKey(song)
        val rev = settings.colorPrefsRevision.value
        val existing = _current.value
        if (!forceRefresh && existing != null && existing.artKey == key && existing.colorRev == rev) {
            if (existing.songId != song.id || existing.path != song.path) {
                _current.value = existing.copy(songId = song.id, path = song.path)
            }
            return
        }
        if (!forceRefresh) {
            val fromNext = _peekNext.value?.takeIf { it.artKey == key && it.colorRev == rev }
            val fromPrev = _peekPrev.value?.takeIf { it.artKey == key && it.colorRev == rev }
            val warm = fromNext ?: fromPrev
            if (warm != null) {
                _current.value = warm.copy(songId = song.id, path = song.path)
                return
            }
        }
        val resolved = themeService.themeFromSong(
            context = context,
            song = song,
            base = baseScheme,
            maxSize = AlbumArtCache.HQ_DECODE_SIZE,
            forceRefresh = forceRefresh,
            surface = ArtColorSurface.COVER,
            loadBitmap = true
        )
        _current.value = Theme(
            artKey = resolved.key,
            songId = song.id,
            path = song.path,
            colors = resolved.colors,
            bitmap = resolved.bitmap,
            colorRev = rev
        )
    }

    suspend fun updateNeighbors(
        context: Context,
        next: Song?,
        prev: Song?,
        baseScheme: ColorScheme
    ) {
        artCache.prefetch(context, listOf(next, prev), AlbumArtCache.HQ_DECODE_SIZE)
        _peekNext.value = next?.let { songToTheme(context, it, baseScheme) }
        _peekPrev.value = prev?.let { songToTheme(context, it, baseScheme) }
    }

    private suspend fun songToTheme(
        context: Context,
        song: Song,
        baseScheme: ColorScheme
    ): Theme {
        val key = artCache.artKey(song)
        val rev = settings.colorPrefsRevision.value
        val cur = _current.value
        if (cur != null && cur.artKey == key && cur.colorRev == rev) {
            return Theme(key, song.id, song.path, cur.colors, cur.bitmap, rev)
        }
        val nextT = _peekNext.value
        if (nextT != null && nextT.artKey == key && nextT.colorRev == rev) {
            return Theme(key, song.id, song.path, nextT.colors, nextT.bitmap, rev)
        }
        val prevT = _peekPrev.value
        if (prevT != null && prevT.artKey == key && prevT.colorRev == rev) {
            return Theme(key, song.id, song.path, prevT.colors, prevT.bitmap, rev)
        }
        val resolved = themeService.themeFromSong(
            context = context,
            song = song,
            base = baseScheme,
            maxSize = AlbumArtCache.HQ_DECODE_SIZE,
            surface = ArtColorSurface.COVER,
            loadBitmap = true
        )
        return Theme(key, song.id, song.path, resolved.colors, resolved.bitmap, rev)
    }

    fun colorsOrFallback(base: ColorScheme): PlayerColors =
        _current.value?.colors ?: fallbackPlayerColors(base)
}
