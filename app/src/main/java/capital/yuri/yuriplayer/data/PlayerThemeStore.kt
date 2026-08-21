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
 * Now-playing palette for the **currently playing song**.
 *
 * Neighbors are warm caches for swipe blend only. They must never become
 * [current] unless their [Theme.songKey] is that playing song. Slow palette
 * extracts are generation-guarded so a skipped track cannot overwrite the new one.
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
        val songKey: String,
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

    @Volatile private var applyGen: Int = 0
    @Volatile private var playingKey: String? = null

    fun artKey(song: Song): String = artCache.artKey(song)

    fun themeIsFor(theme: Theme?, song: Song): Boolean {
        if (theme == null) return false
        return theme.songKey == song.songKey
    }

    fun isShowing(song: Song): Boolean = themeIsFor(_current.value, song)

    /** Colors for [song] only — never a leftover palette from a previous track. */
    fun colorsFor(song: Song?, fallback: PlayerColors): PlayerColors {
        if (song == null) return fallback
        _current.value?.takeIf { it.songKey == song.songKey }?.let { return it.colors }
        _peekNext.value?.takeIf { it.songKey == song.songKey }?.let { return it.colors }
        _peekPrev.value?.takeIf { it.songKey == song.songKey }?.let { return it.colors }
        return fallback
    }

    fun themeFor(song: Song?): Theme? {
        if (song == null) return null
        _current.value?.takeIf { it.songKey == song.songKey }?.let { return it }
        _peekNext.value?.takeIf { it.songKey == song.songKey }?.let { return it }
        _peekPrev.value?.takeIf { it.songKey == song.songKey }?.let { return it }
        return null
    }

    /**
     * Unsafe peek-promote. Prefer [showSong]. Kept for swipe; ignored when the
     * peek is not the playing track.
     */
    fun promoteNext() {
        val n = _peekNext.value ?: return
        if (playingKey != null && n.songKey != playingKey) return
        _peekPrev.value = _current.value
        _current.value = n
        _peekNext.value = null
    }

    fun promotePrev() {
        val p = _peekPrev.value ?: return
        if (playingKey != null && p.songKey != playingKey) return
        _peekNext.value = _current.value
        _current.value = p
        _peekPrev.value = null
    }

    suspend fun showSong(
        context: Context,
        song: Song,
        baseScheme: ColorScheme
    ) {
        playingKey = song.songKey
        val gen = ++applyGen
        if (isShowing(song)) return
        when {
            themeIsFor(_peekNext.value, song) -> {
                _current.value = _peekNext.value
                return
            }
            themeIsFor(_peekPrev.value, song) -> {
                _current.value = _peekPrev.value
                return
            }
        }
        updateCurrent(context, song, baseScheme, requestGen = gen)
    }

    suspend fun updateCurrent(
        context: Context,
        song: Song?,
        baseScheme: ColorScheme,
        forceRefresh: Boolean = false
    ) {
        if (song == null) {
            applyGen += 1
            playingKey = null
            _current.value = null
            return
        }
        playingKey = song.songKey
        updateCurrent(context, song, baseScheme, forceRefresh, requestGen = ++applyGen)
    }

    private suspend fun updateCurrent(
        context: Context,
        song: Song,
        baseScheme: ColorScheme,
        forceRefresh: Boolean = false,
        requestGen: Int
    ) {
        val key = artCache.artKey(song)
        val rev = settings.colorPrefsRevision.value
        val existing = _current.value
        if (!forceRefresh && existing != null && existing.songKey == song.songKey &&
            existing.artKey == key && existing.colorRev == rev
        ) {
            return
        }
        if (!forceRefresh) {
            val warm = _peekNext.value?.takeIf { it.songKey == song.songKey }
                ?: _peekPrev.value?.takeIf { it.songKey == song.songKey }
            if (warm != null) {
                if (requestGen != applyGen) return
                _current.value = warm
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
        if (requestGen != applyGen) return
        _current.value = Theme(
            artKey = resolved.key,
            songId = song.id,
            path = song.path,
            songKey = song.songKey,
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
        if (cur != null && cur.songKey == song.songKey && cur.artKey == key && cur.colorRev == rev) {
            return Theme(key, song.id, song.path, song.songKey, cur.colors, cur.bitmap, rev)
        }
        val nextT = _peekNext.value
        if (nextT != null && nextT.songKey == song.songKey && nextT.artKey == key && nextT.colorRev == rev) {
            return Theme(key, song.id, song.path, song.songKey, nextT.colors, nextT.bitmap, rev)
        }
        val prevT = _peekPrev.value
        if (prevT != null && prevT.songKey == song.songKey && prevT.artKey == key && prevT.colorRev == rev) {
            return Theme(key, song.id, song.path, song.songKey, prevT.colors, prevT.bitmap, rev)
        }
        val resolved = themeService.themeFromSong(
            context = context,
            song = song,
            base = baseScheme,
            maxSize = AlbumArtCache.HQ_DECODE_SIZE,
            surface = ArtColorSurface.COVER,
            loadBitmap = true
        )
        return Theme(key, song.id, song.path, song.songKey, resolved.colors, resolved.bitmap, rev)
    }

    fun colorsOrFallback(base: ColorScheme): PlayerColors =
        _current.value?.colors ?: fallbackPlayerColors(base)
}
