package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumArtResolver
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * Artist profile image: custom/MB profile → seed song album art → person icon.
 */
@Composable
fun ArtistArt(
    artistName: String,
    seedSong: Song? = null,
    size: Dp = 48.dp,
    circular: Boolean = true
) {
    val context = LocalContext.current
    val profileRepo: ArtistProfileRepository = koinInject()
    val profile by profileRepo.observe(artistName).collectAsState(initial = null)
    var bitmap by remember(artistName, profile?.imageUri, seedSong?.songKey) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(artistName, profile?.imageUri, seedSong?.songKey) {
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            val uriStr = profile?.imageUri
            if (!uriStr.isNullOrBlank()) {
                loadImageUri(context, uriStr)?.let { return@withContext it }
            }
            if (seedSong != null) {
                AlbumArtResolver.load(context, seedSong, maxSize = 512)
            } else null
        }
    }

    val shape = if (circular) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = artistName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}

private fun loadImageUri(context: android.content.Context, uriStr: String): Bitmap? {
    return try {
        when {
            uriStr.startsWith("file://") || uriStr.startsWith("/") -> {
                val path = uriStr.removePrefix("file://")
                val f = File(path)
                if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
            }
            else -> {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
