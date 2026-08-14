package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    val repo: PlaylistRepository = koinInject()
    val playlist by repo.observePlaylist(playlistId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pl = playlist
    if (pl == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Playlist not found", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    scope.launch {
                        repo.delete(pl.id)
                        onBack()
                    }
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            PlaylistCoverArt(pl, size = 160.dp)
            Spacer(modifier = Modifier.height(16.dp))
            MarqueeText(
                text = pl.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth()
            )
            if (!pl.description.isNullOrBlank()) {
                Text(
                    pl.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                formatTrackCount(pl.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            IconButton(
                onClick = {
                    if (pl.songs.isNotEmpty()) onPlay(pl.songs, 0)
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            itemsIndexed(pl.songs, key = { i, s -> "$i-${s.songKey}" }) { index, song ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    scope.launch { repo.move(pl.id, index, index - 1) }
                                }
                            },
                            enabled = index > 0
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = {
                                if (index < pl.songs.lastIndex) {
                                    scope.launch { repo.move(pl.id, index, index + 1) }
                                }
                            },
                            enabled = index < pl.songs.lastIndex
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SwipeAddSongRow(
                            song = song,
                            onClick = { onPlay(pl.songs, index) },
                            onSwipeAdd = {
                                onAddToQueue(song)
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                            },
                            isPlaying = song.isSameAs(nowPlaying),
                            isPlaybackActive = isPlaybackActive
                        )
                    }
                }
            }
        }
    }
}
