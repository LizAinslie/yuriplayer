package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.AlbumCoverPrefs
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.CoverCandidate
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.albumKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * Shows [AlbumCoverPickerSheet] for [album], loads multi-source candidates,
 * persists preference on [albumKey], invalidates art cache.
 *
 * Custom "+" path: GetContent → crop → copy into filesDir/covers so the URI
 * survives SAF permission loss and always loads in [AlbumArtCache].
 */
@Composable
fun AlbumCoverPickerHost(
    album: AlbumItem,
    onDismiss: () -> Unit
) {
    val catalog: CatalogRepository = koinInject()
    val coverPrefs: AlbumCoverPrefs = koinInject()
    val artCache: AlbumArtCache = koinInject()
    val enrichment: MetadataEnrichmentService = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val aKey = remember(album.name, album.artist) { albumKey(album.name, album.artist) }
    var candidates by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var preferred by remember { mutableStateOf(coverPrefs.preferredUri(aKey)) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(aKey, album.songs.size) {
        candidates = catalog.coverCandidatesForAlbum(aKey, album.songs)
        preferred = coverPrefs.preferredUri(aKey)
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) cropUri = uri
    }

    fun apply(uri: String?) {
        scope.launch {
            coverPrefs.setPreferredUri(aKey, uri)
            if (uri != null && uri.startsWith("http", ignoreCase = true)) {
                catalog.applyAlbumCover(aKey, coverPath = null, coverUrl = uri, mbid = null)
            }
            artCache.invalidateAlbum(aKey)
            enrichment.bumpCoverGeneration()
            preferred = uri
            Toast.makeText(
                context,
                if (uri == null) "Using default cover (local preferred)"
                else "Cover updated",
                Toast.LENGTH_SHORT
            ).show()
            onDismiss()
        }
    }

    if (cropUri != null) {
        ImageCropScreen(
            sourceUri = cropUri!!,
            title = "Crop album cover",
            aspect = 1f,
            onCancel = { cropUri = null },
            onCropped = { cropped ->
                cropUri = null
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        persistCoverToFiles(context, aKey, cropped)
                    }
                    if (saved == null) {
                        Toast.makeText(context, "Could not save cover", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    coverPrefs.setPreferredUri(aKey, saved)
                    catalog.applyAlbumCover(aKey, coverPath = saved, coverUrl = null, mbid = null)
                    artCache.invalidateAlbum(aKey)
                    enrichment.bumpCoverGeneration()
                    preferred = saved
                    Toast.makeText(context, "Cover updated", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        )
        return
    }

    AlbumCoverPickerSheet(
        albumTitle = album.displayName,
        candidates = candidates,
        preferredUri = preferred,
        onDismiss = onDismiss,
        onPick = { apply(it.uri) },
        onUseDefault = { apply(null) },
        onPickCustomFile = { pickImage.launch("image/*") }
    )
}

/** Copy a cropped image into durable app storage; return file:// URI string. */
private fun persistCoverToFiles(
    context: android.content.Context,
    albumKeyStr: String,
    source: Uri
): String? {
    return try {
        val dir = File(context.filesDir, "covers").also { it.mkdirs() }
        val name = MetadataEnrichmentService.sanitizeFileName(albumKeyStr) + "-custom.jpg"
        val dest = File(dir, name)
        context.contentResolver.openInputStream(source)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (!dest.isFile || dest.length() == 0L) return null
        dest.absolutePath
    } catch (_: Exception) {
        null
    }
}
