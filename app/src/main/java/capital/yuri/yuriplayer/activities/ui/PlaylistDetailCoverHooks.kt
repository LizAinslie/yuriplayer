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
 * Shared multi-cover + crop host for playlist detail.
 * Keeps the large detail screen thinner while exposing openCoverPicker().
 */
@Composable
fun rememberPlaylistCoverHost(playlistId: String): PlaylistCoverHost {
    val repo: PlaylistRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showPicker by remember { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var cropAsSecret by remember { mutableStateOf(false) }

    return remember(playlistId) {
        PlaylistCoverHost(
            showPicker = { showPicker },
            setShowPicker = { showPicker = it },
            cropUri = { cropUri },
            setCropUri = { cropUri = it },
            cropAsSecret = { cropAsSecret },
            setCropAsSecret = { cropAsSecret = it },
            onCropped = { uri ->
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
                    showPicker = true
                }
            }
        )
    }.also {
        // keep latest lambdas stable for callers that read state each frame
        it.showPickerState = showPicker
        it.cropUriState = cropUri
        it.cropAsSecretState = cropAsSecret
    }
}

class PlaylistCoverHost(
    private val showPicker: () -> Boolean,
    private val setShowPicker: (Boolean) -> Unit,
    private val cropUri: () -> Uri?,
    private val setCropUri: (Uri?) -> Unit,
    private val cropAsSecret: () -> Boolean,
    private val setCropAsSecret: (Boolean) -> Unit,
    val onCropped: (Uri) -> Unit
) {
    var showPickerState: Boolean = false
    var cropUriState: Uri? = null
    var cropAsSecretState: Boolean = false

    fun openPicker() = setShowPicker(true)
    fun dismissPicker() = setShowPicker(false)
    fun requestCrop(uri: Uri, secret: Boolean) {
        setCropAsSecret(secret)
        setShowPicker(false)
        setCropUri(uri)
    }
    fun clearCrop() = setCropUri(null)

    fun isShowingPicker(): Boolean = showPicker()
    fun currentCropUri(): Uri? = cropUri()
}

@Composable
fun PlaylistCoverHostUi(
    host: PlaylistCoverHost,
    playlistId: String,
    playlistName: String
) {
    val crop = host.currentCropUri()
    if (crop != null) {
        ImageCropScreen(
            sourceUri = crop,
            title = "Crop cover",
            aspect = 1f,
            onCancel = { host.clearCrop() },
            onCropped = { uri ->
                host.clearCrop()
                host.onCropped(uri)
            }
        )
        return
    }
    if (host.isShowingPicker()) {
        PlaylistCoverPickerSheet(
            playlistId = playlistId,
            playlistName = playlistName,
            onDismiss = { host.dismissPicker() },
            onRequestCrop = { uri, secret -> host.requestCrop(uri, secret) }
        )
    }
}
