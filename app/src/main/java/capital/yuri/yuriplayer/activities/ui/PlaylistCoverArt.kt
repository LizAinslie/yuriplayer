package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumArtResolver
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistCover
import capital.yuri.yuriplayer.data.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlist cover: custom image → first track album art → placeholder icon.
 */
@Composable
fun PlaylistCoverArt(playlist: Playlist, size: Dp = 56.dp) {
    val context = LocalContext.current
    val cover = remember(playlist.id, playlist.customImageUri, playlist.songs.size) {
        PlaylistRepository.coverFor(playlist)
    }
    var bitmap by remember(playlist.id, playlist.customImageUri, playlist.songs.firstOrNull()?.songKey) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(playlist.id, playlist.customImageUri, playlist.songs.firstOrNull()?.songKey) {
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            when (cover.mode) {
                PlaylistCover.CoverMode.CUSTOM -> {
                    val uri = cover.customUri ?: return@withContext null
                    loadUriBitmap(context, uri)
                }
                PlaylistCover.CoverMode.SINGLE,
                PlaylistCover.CoverMode.COLLAGE -> {
                    // Prefer embedded/folder art from first track with art
                    playlist.songs.firstOrNull()?.let { song ->
                        AlbumArtResolver.load(context, song, maxSize = 512)
                    } ?: cover.artUris.firstOrNull()?.let { loadUriBitmap(context, it) }
                }
                PlaylistCover.CoverMode.EMPTY -> null
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
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

private fun loadUriBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        when (uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.path)
            else -> context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }
    } catch (_: Exception) {
        null
    }
}
