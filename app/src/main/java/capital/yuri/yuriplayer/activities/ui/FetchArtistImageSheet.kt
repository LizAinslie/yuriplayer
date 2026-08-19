package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.source.ArtistImageCandidate
import capital.yuri.yuriplayer.data.source.ArtistImageKind
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistNameMatch
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchArtistImageSheet(
    artistName: String,
    kind: ArtistImageKind = ArtistImageKind.PROFILE,
    onDismiss: () -> Unit,
    onPicked: (Uri) -> Unit
) {
    val info: ArtistInfoService = koinInject()
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<ArtistImageCandidate>>(emptyList()) }
    var failedUrls by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Always pull PROFILE + BANNER sources and merge so either picker can use any hit.
    LaunchedEffect(artistName) {
        loading = true
        error = null
        failedUrls = emptySet()
        candidates = runCatching {
            coroutineScope {
                val profile = async {
                    info.gatherImageCandidates(artistName, ArtistImageKind.PROFILE)
                }
                val banner = async {
                    info.gatherImageCandidates(artistName, ArtistImageKind.BANNER)
                }
                mergeCandidates(profile.await() + banner.await())
            }
        }.onFailure { error = it.message }.getOrDefault(emptyList())
        loading = false
    }

    val visible = remember(candidates, failedUrls) {
        candidates.filter { it.url !in failedUrls }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                when (kind) {
                    ArtistImageKind.PROFILE -> "Fetch artist image"
                    ArtistImageKind.BANNER -> "Fetch banner image"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "All sources combined (profile + banner). Original aspect shown — crop next. " +
                    "MusicBrainz · Wikipedia · Wikidata · Deezer · AudioDB · Discogs · Jellyfin",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)

                candidates.isEmpty() -> Text(
                    "No images found for $artistName",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(24.dp)
                )

                visible.isEmpty() -> Text(
                    "Images failed to load",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(24.dp)
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(420.dp)
                ) {
                    items(visible, key = { it.url }) { c ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onPicked(Uri.parse(c.url)) },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = c.url,
                                contentDescription = c.label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                onError = {
                                    failedUrls = failedUrls + c.url
                                }
                            )
                            Text(
                                c.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                    )
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dedupe by image fingerprint; keep the larger / non-thumb variant. */
private fun mergeCandidates(list: List<ArtistImageCandidate>): List<ArtistImageCandidate> {
    val out = LinkedHashMap<String, ArtistImageCandidate>()
    list.forEach { c ->
        if (c.url.isBlank()) return@forEach
        if (c.url.contains("/images/artist//")) return@forEach
        if (c.url.contains("artist-default")) return@forEach

        val key = ArtistNameMatch.imageFingerprint(c.url)
        val existing = out[key]
        if (existing == null) {
            out[key] = c
            return@forEach
        }
        val newArea = (c.width ?: 0) * (c.height ?: 0).let {
            if (it > 0) it else ArtistNameMatch.imageSizeHint(c.url)
        }
        val oldArea = (existing.width ?: 0) * (existing.height ?: 0).let {
            if (it > 0) it else ArtistNameMatch.imageSizeHint(existing.url)
        }
        val existingThumb = existing.label.contains("thumb", true) ||
            existing.url.contains("thumb", true)
        val newThumb = c.label.contains("thumb", true) || c.url.contains("thumb", true)
        if (newArea > oldArea || (existingThumb && !newThumb)) {
            out[key] = c
        }
    }
    return out.values.toList()
}
