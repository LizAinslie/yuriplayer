package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistCover
import capital.yuri.yuriplayer.data.PlaylistRepository
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

/**
 * Playlist cover:
 * - **Custom** image: Coil with explicit size + stable cache keys (no crossfade for local files)
 * - **Track art** fallback: reuse [AlbumArt] / [AlbumArtCache] so list thumbs stay warm
 * - Empty → placeholder icon
 */
@Composable
fun PlaylistCoverArt(playlist: Playlist, size: Dp = 56.dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cover = remember(playlist.id, playlist.customImageUri, playlist.songs.size) {
        PlaylistRepository.coverFor(playlist)
    }
    val px = with(density) { size.roundToPx().coerceAtLeast(1) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (cover.mode) {
            PlaylistCover.CoverMode.CUSTOM -> {
                val uri = cover.customUri?.toString()
                if (uri != null) {
                    val local = uri.startsWith("file:") || uri.startsWith("/")
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .size(Size(px, px))
                            .memoryCacheKey("pl-cover:${playlist.id}:$uri")
                            .diskCacheKey("pl-cover:${playlist.id}:$uri")
                            .crossfade(!local)
                            .build(),
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlaceholderIcon(empty = false)
                }
            }
            PlaylistCover.CoverMode.SINGLE,
            PlaylistCover.CoverMode.COLLAGE -> {
                // Prefer the same warm AlbumArtCache path used by song rows
                val seed = playlist.songs.firstOrNull()
                if (seed != null) {
                    AlbumArt(
                        song = seed,
                        size = size,
                        corner = 6.dp
                    )
                } else {
                    PlaceholderIcon(empty = false)
                }
            }
            PlaylistCover.CoverMode.EMPTY -> PlaceholderIcon(empty = true)
        }
    }
}

@Composable
private fun PlaceholderIcon(empty: Boolean) {
    Icon(
        imageVector = if (empty) Icons.Default.Folder else Icons.Default.QueueMusic,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
