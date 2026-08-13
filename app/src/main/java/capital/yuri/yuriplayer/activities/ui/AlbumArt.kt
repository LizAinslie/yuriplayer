package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import capital.yuri.yuriplayer.data.Song

@Composable
fun AlbumArt(
    song: Song?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 8.dp
) {
    val context = LocalContext.current
    var bitmap by remember(song?.path, song?.contentUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(song?.path, song?.contentUri) {
        bitmap = null
        if (song != null) {
            bitmap = AlbumArtResolver.load(context, song)
        }
    }

    val shape = RoundedCornerShape(corner)
    val boxMod = if (size != null) modifier.size(size).clip(shape) else modifier.clip(shape)

    Box(
        modifier = boxMod.background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = song?.displayAlbum,
                contentScale = ContentScale.Crop,
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
