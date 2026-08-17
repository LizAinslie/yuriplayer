package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistCover
import capital.yuri.yuriplayer.data.PlaylistRepository
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Playlist cover via Coil (static + animated GIF).
 * custom image → first track art URI → placeholder.
 */
@Composable
fun PlaylistCoverArt(playlist: Playlist, size: Dp = 56.dp) {
    val context = LocalContext.current
    val cover = remember(playlist.id, playlist.customImageUri, playlist.songs.size) {
        PlaylistRepository.coverFor(playlist)
    }

    val model: Any? = remember(playlist.id, playlist.customImageUri, playlist.songs.firstOrNull()?.songKey) {
        when (cover.mode) {
            PlaylistCover.CoverMode.CUSTOM -> cover.customUri
            PlaylistCover.CoverMode.SINGLE,
            PlaylistCover.CoverMode.COLLAGE ->
                cover.artUris.firstOrNull()
                    ?: playlist.songs.firstOrNull()?.let { song ->
                        song.albumArtUri?.let { Uri.parse(it) }
                    }
            PlaylistCover.CoverMode.EMPTY -> null
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = when (cover.mode) {
                    PlaylistCover.CoverMode.EMPTY -> Icons.Default.Folder
                    else -> Icons.Default.QueueMusic
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
