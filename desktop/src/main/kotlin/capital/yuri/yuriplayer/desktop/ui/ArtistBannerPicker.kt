package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.dialog.InWindowPanel
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

    val sections = remember(candidates) {
        candidates.groupBy { sourceLabel(it.sourceId) }.toList()
    }

    InWindowPanel(onDismiss = onDismiss, modifier = Modifier.width(640.dp).heightIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Choose a header", style = MaterialTheme.typography.titleLarge)
            Text(
                artistName,
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
                    modifier = Modifier.heightIn(max = 420.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    sections.forEach { (title, items) ->
                        item(span = { GridItemSpan(3) }, key = "h-$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(items, key = { it.url }) { c ->
                            BannerTile(c) { onPicked(c) }
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

@Composable
private fun BannerTile(
    candidate: ArtistImageCandidate,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .clipToBounds()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        ) {
            CoverArt(
                model = candidate.url,
                modifier = Modifier.fillMaxSize(),
                square = false,
                corner = 0.dp,
                contentScale = ContentScale.Crop
            )
        }
        Text(
            candidate.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun sourceLabel(id: String) = when (id.lowercase()) {
    "wikipedia" -> "Wikipedia"
    "theaudiodb", "audiodb" -> "TheAudioDB"
    "deezer" -> "Deezer"
    "musicbrainz" -> "MusicBrainz"
    "wikidata" -> "Wikidata"
    "jellyfin" -> "Jellyfin"
    "navidrome", "subsonic" -> "Navidrome"
    else -> id.replaceFirstChar { it.uppercase() }
}
