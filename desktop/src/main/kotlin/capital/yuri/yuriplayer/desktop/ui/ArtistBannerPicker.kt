package capital.yuri.yuriplayer.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.core.artist.ArtistImageCandidate
import capital.yuri.yuriplayer.core.artist.ArtistInfoClient

@Composable
fun ArtistBannerPicker(
    artistName: String,
    client: ArtistInfoClient,
    onDismiss: () -> Unit,
    onPicked: (ArtistImageCandidate) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<ArtistImageCandidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistName) {
        loading = true
        error = null
        candidates = runCatching { client.bannerCandidates(artistName) }
            .onFailure { error = it.message }
            .getOrDefault(emptyList())
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.width(640.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Choose a header", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Wikipedia and TheAudioDB · $artistName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    candidates.isEmpty() -> Text(
                        "Couldn't find headers for $artistName",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.height(360.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates, key = { it.url }) { c ->
                            Column {
                                CoverArt(
                                    model = c.url,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onPicked(c) }
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    corner = 8.dp,
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    c.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}
