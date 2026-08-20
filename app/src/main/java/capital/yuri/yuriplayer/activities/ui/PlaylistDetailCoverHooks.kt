package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import capital.yuri.yuriplayer.data.PlaylistRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Multi-cover + crop overlay for a playlist.
 *
 * Usage from detail:
 * ```
 * var showCovers by remember { mutableStateOf(false) }
 * PlaylistMultiCoverOverlay(
 *     playlistId = id,
 *     playlistName = name,
 *     open = showCovers,
 *     onOpenChange = { showCovers = it }
 * )
 * // changeCover / edit cover button → showCovers = true
 * ```
 */
@Composable
fun PlaylistMultiCoverOverlay(
    playlistId: String,
    playlistName: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit
) {
    val repo: PlaylistRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var cropAsSecret by remember { mutableStateOf(false) }

    val crop = cropUri
    if (crop != null) {
        ImageCropScreen(
            sourceUri = crop,
            title = "Crop cover",
            aspect = 1f,
            onCancel = { cropUri = null },
            onCropped = { uri ->
                cropUri = null
                scope.launch {
                    repo.addCover(
                        playlistId = playlistId,
                        sourceUri = uri.toString(),
                        isSecret = cropAsSecret,
                        makeActive = !cropAsSecret
                    )
                    Toast.makeText(
                        context,
                        if (cropAsSecret) "Secret cover added" else "Cover added",
                        Toast.LENGTH_SHORT
                    ).show()
                    cropAsSecret = false
                    onOpenChange(true)
                }
            }
        )
        return
    }

    if (open) {
        PlaylistCoverPickerSheet(
            playlistId = playlistId,
            playlistName = playlistName,
            onDismiss = { onOpenChange(false) },
            onRequestCrop = { uri, secret ->
                cropAsSecret = secret
                onOpenChange(false)
                cropUri = uri
            }
        )
    }
}
