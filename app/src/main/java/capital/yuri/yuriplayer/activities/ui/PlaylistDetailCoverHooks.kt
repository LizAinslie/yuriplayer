package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Important: while cropping we must **not** call [onOpenChange](false).
 * [PlaylistCoverGlobalHost] tears down this whole subtree when open becomes
 * false, which would drop [cropUri] and never show [ImageCropScreen].
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
    var showSheet by remember { mutableStateOf(open) }

    LaunchedEffect(open) {
        if (open) {
            showSheet = true
            // Fresh open — drop any stale crop from a previous session
            if (cropUri == null) return@LaunchedEffect
        } else if (cropUri == null) {
            showSheet = false
        }
    }

    val crop = cropUri
    if (crop != null) {
        ImageCropScreen(
            sourceUri = crop,
            title = "Crop cover",
            aspect = 1f,
            onCancel = {
                cropUri = null
                showSheet = true
            },
            onCropped = { uri ->
                cropUri = null
                scope.launch {
                    val slot = repo.addCover(
                        playlistId = playlistId,
                        sourceUri = uri.toString(),
                        isSecret = cropAsSecret,
                        makeActive = !cropAsSecret
                    )
                    Toast.makeText(
                        context,
                        when {
                            slot == null -> "Could not save cover"
                            cropAsSecret -> "Secret cover added"
                            else -> "Cover added"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    cropAsSecret = false
                    showSheet = true
                }
            }
        )
        return
    }

    if (open && showSheet) {
        PlaylistCoverPickerSheet(
            playlistId = playlistId,
            playlistName = playlistName,
            onDismiss = {
                showSheet = false
                onOpenChange(false)
            },
            onRequestCrop = { uri, secret ->
                cropAsSecret = secret
                // Keep host alive — only swap sheet → crop
                cropUri = uri
            }
        )
    }
}
