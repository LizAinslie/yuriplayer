package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Shows [AlbumCoverPickerSheet] for [album], loads multi-source candidates,
 * persists preference on [albumKey], invalidates art cache.
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

    LaunchedEffect(aKey, album.songs.size) {
        candidates = catalog.coverCandidatesForAlbum(aKey, album.songs)
        preferred = coverPrefs.preferredUri(aKey)
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

    AlbumCoverPickerSheet(
        albumTitle = album.displayName,
        candidates = candidates,
        preferredUri = preferred,
        onDismiss = onDismiss,
        onPick = { apply(it.uri) },
        onUseDefault = { apply(null) }
    )
}
