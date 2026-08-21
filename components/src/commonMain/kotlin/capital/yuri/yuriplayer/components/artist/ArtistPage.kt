package capital.yuri.yuriplayer.components.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.AlbumCard
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.list.formatTime
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.components.model.ArtistPageModel
import capital.yuri.yuriplayer.components.model.TrackRowModel

private enum class DiscoFilter { Popular, Albums, Singles }

@Composable
fun ArtistPage(
    artist: ArtistPageModel,
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTrack: (Int) -> Unit,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val mini by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var disco by remember { mutableStateOf(DiscoFilter.Popular) }
    val releases = remember(artist.discography, disco) {
        when (disco) {
            DiscoFilter.Popular -> artist.discography
            DiscoFilter.Albums -> artist.discography.filter { it.releaseKind() == "Album" }
            DiscoFilter.Singles -> artist.discography.filter { it.releaseKind() != "Album" }
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ArtistBanner(artist)
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledIconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = onShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Column(Modifier.weight(1.2f)) {
                        Text(
                            "Popular",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        artist.popular.forEachIndexed { i, track ->
                            PopularRow(
                                index = i + 1,
                                track = track,
                                onClick = { onTrack(i) }
                            )
                        }
                    }
                    if (artist.likedCount > 0) {
                        Column(Modifier.weight(0.8f)) {
                            Text(
                                "You Liked",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CoverArt(
                                        model = artist.liked.firstOrNull()?.artworkUri ?: artist.artworkUri,
                                        size = 72.dp,
                                        corner = 36.dp,
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            "${artist.likedCount} songs · ${artist.likedReleaseCount} releases",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "By ${artist.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Discography",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {}) { Text("Show all") }
                }
                Row(
                    Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = disco == DiscoFilter.Popular,
                        onClick = { disco = DiscoFilter.Popular },
                        label = { Text("Popular releases") }
                    )
                    FilterChip(
                        selected = disco == DiscoFilter.Albums,
                        onClick = { disco = DiscoFilter.Albums },
                        label = { Text("Albums") }
                    )
                    FilterChip(
                        selected = disco == DiscoFilter.Singles,
                        onClick = { disco = DiscoFilter.Singles },
                        label = { Text("Singles and EPs") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(releases, key = { it.id }) { album ->
                        AlbumCard(
                            album = album.copy(artist = "${album.year ?: ""} · ${album.releaseKind()}".trim(' ', '·')),
                            onClick = { onOpenAlbum(album) }
                        )
                    }
                }
            }
            if (artist.appearsOn.isNotEmpty()) {
                item {
                    Text(
                        "Appears on",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(artist.appearsOn, key = { "ao-${it.id}" }) { album ->
                            AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                        }
                    }
                }
            }
            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)
                )
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        CoverArt(
                            model = artist.artworkUri,
                            modifier = Modifier.fillMaxSize(),
                            corner = 0.dp,
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                                    )
                                )
                        )
                        Text(
                            artist.about ?: artist.stats,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
        if (mini) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(artist.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ArtistBanner(artist: ArtistPageModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        CoverArt(
            model = artist.bannerUri ?: artist.artworkUri,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.55f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            Text(
                artist.name,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                lineHeight = 60.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                artist.stats,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun PopularRow(index: Int, track: TrackRowModel, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            index.toString(),
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        CoverArt(model = track.artworkUri, size = 40.dp, corner = 4.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            track.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium
        )
        Text(
            formatTime(track.durationMs ?: 0L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

internal fun AlbumPageModel.releaseKind(): String = when {
    tracks.size <= 3 -> "Single"
    tracks.size <= 7 -> "EP"
    else -> "Album"
}
