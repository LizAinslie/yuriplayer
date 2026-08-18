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
import coil3.compose.AsyncImage
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
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistName, kind) {
        loading = true
        error = null
        candidates = runCatching { info.gatherImageCandidates(artistName, kind) }
            .onFailure { error = it.message }
            .getOrDefault(emptyList())
        loading = false
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
                "MusicBrainz · Wikipedia · Wikidata · Deezer · TheAudioDB — pick one to crop.",
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

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(420.dp)
                ) {
                    items(candidates, key = { it.url }) { c ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(if (kind == ArtistImageKind.BANNER) 16f / 9f else 1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onPicked(Uri.parse(c.url)) }
                        ) {
                            AsyncImage(
                                model = c.url,
                                contentDescription = c.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize()
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
