package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.Song
import org.koin.compose.koinInject

/**
 * Song / album cover.
 *
 * Display [size] drives decode tier:
 * - row-sized (<= 80.dp) → [AlbumArtCache.THUMB_DECODE_SIZE] (128px), kept in the
 *   large thumb LRU so queue / playlist / library scrolling stays smooth
 * - mid (<= 160.dp) → 256px
 * - larger / unspecified → hero tier (512px)
 *
 * Now Playing uses [PlayerThemeStore] + [AlbumArtCache.HQ_DECODE_SIZE], not this
 * composable.
 */
@Composable
fun AlbumArt(
    song: Song?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 8.dp
) {
    val context = LocalContext.current
    val artCache: AlbumArtCache = koinInject()
    val enrichment: MetadataEnrichmentService = koinInject()
    val coverGen by enrichment.coverGeneration.collectAsState()

    // Song rows are typically 40–56dp; treat anything <= 80dp as a list thumb.
    val decodeSize = remember(size) {
        when {
            size == null -> AlbumArtCache.HERO_DECODE_SIZE
            size <= 80.dp -> AlbumArtCache.THUMB_DECODE_SIZE
            size <= 160.dp -> 256
            else -> AlbumArtCache.HERO_DECODE_SIZE
        }
    }

    val baseKey = remember(song?.path, song?.contentUri, song?.album, song?.artist, coverGen) {
        song?.let { artCache.artKey(it) }
    }

    // Seed from memory so recycled list items show art immediately.
    var bitmap by remember(baseKey, decodeSize, coverGen) {
        mutableStateOf(
            if (song != null) artCache.peek(song, decodeSize) else null
        )
    }

    LaunchedEffect(baseKey, decodeSize, coverGen) {
        if (song == null) {
            bitmap = null
            return@LaunchedEffect
        }
        // Do not clear to null first — keeps previous frame while loading and
        // avoids the unload flash when scrolling back over warm cache entries.
        val loaded = artCache.get(context, song, decodeSize)
        bitmap = loaded
    }

    val shape = RoundedCornerShape(corner)
    val boxMod = if (size != null) {
        modifier.size(size).clip(shape)
    } else {
        modifier.aspectRatio(1f).clip(shape)
    }

    Box(
        modifier = boxMod.background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = song?.displayAlbum,
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                filterQuality = if (decodeSize >= AlbumArtCache.HERO_DECODE_SIZE) {
                    FilterQuality.High
                } else {
                    FilterQuality.Low
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}
