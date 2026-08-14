package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class MyStuffSection { Playlists, LocalFiles }

private enum class LocalBrowse { Songs, Albums, Artists }

@Composable
fun MyStuffScreen(
    library: LibraryIndex,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit
) {
    val playlistsRepo: PlaylistRepository = koinInject()
    val playlists by playlistsRepo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var section by remember { mutableStateOf(MyStuffSection.Playlists) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    var localBrowse by remember { mutableStateOf(LocalBrowse.Songs) }
    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    val allSongs by library.songs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MyStuffSection.entries.forEach { s ->
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = {
                        Text(
                            when (s) {
                                MyStuffSection.Playlists -> "Playlists"
                                MyStuffSection.LocalFiles -> "Local files"
                            }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (section == MyStuffSection.Playlists) {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New playlist")
                }
            }
        }

        when (section) {
            MyStuffSection.Playlists -> {
                if (playlists.isEmpty()) {
                    PlaceholderScreen(
                        title = "No playlists yet",
                        body = "Tap + to create one. Add songs from Explore or album pages."
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(playlists, key = { it.id }) { pl ->
                            PlaylistRow(pl) { onOpenPlaylist(pl) }
                        }
                    }
                }
            }
            MyStuffSection.LocalFiles -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocalBrowse.entries.forEach { b ->
                        FilterChip(
                            selected = localBrowse == b,
                            onClick = { localBrowse = b },
                            label = { Text(b.name) }
                        )
                    }
                }
                if (localBrowse == LocalBrowse.Songs) {
                    SortDropdown(sortMode) { sortMode = it }
                }
                when (localBrowse) {
                    LocalBrowse.Songs -> {
                        val songs = remember(allSongs, sortMode) {
                            LibraryIndex.sortSongs(allSongs, sortMode)
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(songs, key = { _, s -> s.id to s.path }) { index, song ->
                                SwipeAddSongRow(
                                    song = song,
                                    onClick = { onPlay(songs, index) },
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
                    LocalBrowse.Albums -> {
                        val albums = remember(allSongs) { library.albums(taggedOnly = false) }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(albums, key = { "${it.name}|${it.artist}" }) { album ->
                                AlbumRow(album, onClick = { onOpenAlbum(album) })
                            }
                        }
                    }
                    LocalBrowse.Artists -> {
                        val artists = remember(allSongs) { library.artists(taggedOnly = false) }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(artists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                                ArtistRow(artist) { onOpenArtist(artist) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val pl = playlistsRepo.create(newName.ifBlank { "New playlist" })
                            showCreate = false
                            newName = ""
                            onOpenPlaylist(pl)
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistCoverArt(playlist, size = 56.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                if (playlist.trackCount > 0) formatTrackCount(playlist.trackCount) else "Playlist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun PlaylistCoverArt(playlist: Playlist, size: androidx.compose.ui.unit.Dp) {
    val cover = remember(playlist) { PlaylistRepository.coverFor(playlist) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (cover.mode) {
            capital.yuri.yuriplayer.data.PlaylistCover.CoverMode.CUSTOM,
            capital.yuri.yuriplayer.data.PlaylistCover.CoverMode.SINGLE -> {
                val uri = cover.customUri ?: cover.artUris.firstOrNull()
                if (uri != null) {
                    // Reuse AlbumArt path via a synthetic seed song not available — show icon fallback
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                }
            }
            capital.yuri.yuriplayer.data.PlaylistCover.CoverMode.COLLAGE -> {
                // Simple 2x2 placeholder until full collage painter lands
                Icon(
                    Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            capital.yuri.yuriplayer.data.PlaylistCover.CoverMode.EMPTY -> {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
