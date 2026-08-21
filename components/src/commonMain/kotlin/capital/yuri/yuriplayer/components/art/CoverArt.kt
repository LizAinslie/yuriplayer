package capital.yuri.yuriplayer.components.art

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Shared cover. Coil 3 owns memory + disk cache (configured per platform
 * against [capital.yuri.yuriplayer.core.platform.AppDirectories.cacheDir]).
 */
@Composable
fun CoverArt(
    model: Any?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 8.dp,
    contentDescription: String? = null
) {
    val shape = RoundedCornerShape(corner)
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        modifier = sized
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model == null || (model is String && model.isBlank())) {
            Icon(
                Icons.Default.Album,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
