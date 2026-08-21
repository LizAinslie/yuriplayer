package capital.yuri.yuriplayer.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.model.CoverRef
import capital.yuri.yuriplayer.components.model.TrackRowModel

enum class SidebarTab { NowPlaying, Queue, History }

@Composable
fun NowPlayingSidebar(
    track: CoverRef?,
    queue: List<TrackRowModel>,
    history: List<TrackRowModel>,
    onQueueTrack: (TrackRowModel) -> Unit,
    onHistoryTrack: (TrackRowModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Now playing") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Queue") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("History") })
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> NowPlayingPane(track)
            1 -> TrackPane(queue, emptyMessage = "Queue is empty", onTrack = onQueueTrack)
            else -> TrackPane(history, emptyMessage = "No history yet", onTrack = onHistoryTrack)
        }
    }
}

@Composable
private fun NowPlayingPane(track: CoverRef?) {
    Column {
        CoverArt(
            model = track?.artworkUri,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            corner = 12.dp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            track?.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            track?.subtitle ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrackPane(
    tracks: List<TrackRowModel>,
    emptyMessage: String,
    onTrack: (TrackRowModel) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            emptyMessage,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    LazyColumn {
        items(tracks, key = { it.id }) { row ->
            TrackRow(track = row, onClick = { onTrack(row) }, showCover = true, showAlbum = true)
        }
    }
}
