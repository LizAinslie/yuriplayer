package capital.yuri.yuriplayer.components.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.layout.WindowWidthClass
import capital.yuri.yuriplayer.components.layout.windowWidthClass
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.components.theme.AlbumArtBackdrop
import capital.yuri.yuriplayer.components.theme.rememberCoverColors

@Composable
fun AlbumPage(
    album: AlbumPageModel,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTrack: (Int) -> Unit,
    onEdit: () -> Unit = {},
    onEditTrack: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coverColors by rememberCoverColors(
        artworkUri = album.artworkUri,
        audioPath = album.tracks.firstOrNull()?.id
    )
    AlbumArtBackdrop(
        wash = coverColors?.container ?: Color.Transparent,
        accent = coverColors?.accent ?: Color.Transparent,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthClass = windowWidthClass(maxWidth)
            when (widthClass) {
                WindowWidthClass.Compact -> AlbumPageCompact(
                    album, playing, onBack, onPlay, onTrack, onEdit, onEditTrack
                )
                WindowWidthClass.Medium,
                WindowWidthClass.Expanded -> AlbumPageWide(
                    album, playing, onBack, onPlay, onTrack, onEdit, onEditTrack
                )
            }
        }
    }
}

@Composable
private fun AlbumPageCompact(
    album: AlbumPageModel,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTrack: (Int) -> Unit,
    onEdit: () -> Unit,
    onEditTrack: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text("Album", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit album")
            }
        }
        CoverArt(
            model = album.artworkUri,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .aspectRatio(1f),
            corner = 12.dp
        )
        AlbumMeta(album, playing, onPlay, Modifier.padding(20.dp))
        TrackList(album, onTrack, onEditTrack)
    }
}

@Composable
private fun AlbumPageWide(
    album: AlbumPageModel,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTrack: (Int) -> Unit,
    onEdit: () -> Unit,
    onEditTrack: (Int) -> Unit
) {
    Row(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .padding(end = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit album")
                }
            }
            CoverArt(
                model = album.artworkUri,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                corner = 12.dp
            )
            Spacer(Modifier.height(16.dp))
            AlbumMeta(album, playing, onPlay)
        }
        TrackList(album, onTrack, onEditTrack, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AlbumMeta(
    album: AlbumPageModel,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            album.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        val bits = buildList {
            album.year?.let { add(it.toString()) }
            add("${album.tracks.size} tracks")
        }
        Text(
            bits.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 4.dp)
        )
        IconButton(onClick = onPlay, modifier = Modifier.padding(top = 8.dp)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TrackList(
    album: AlbumPageModel,
    onTrack: (Int) -> Unit,
    onEditTrack: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val discs = album.tracks.groupBy { it.discNumber ?: 1 }
    val multi = discs.size > 1
    LazyColumn(modifier.fillMaxSize()) {
        discs.toSortedMap().forEach { (disc, tracks) ->
            if (multi) {
                item(key = "disc-$disc") {
                    Text(
                        "Disc $disc",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { _, track ->
                val global = album.tracks.indexOfFirst { it.id == track.id }
                TrackRow(
                    track = track,
                    onClick = { onTrack(global.coerceAtLeast(0)) },
                    onLongClick = { onEditTrack(global.coerceAtLeast(0)) },
                    showCover = false,
                    showAlbum = false
                )
            }
        }
    }
}
