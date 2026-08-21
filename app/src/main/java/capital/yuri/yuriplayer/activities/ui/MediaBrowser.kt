package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject

enum class MediaBrowserSection { Songs, Albums, Artists, Playlists }

/**
 * Lookup / data source for [MediaBrowser].
 * Callers supply how to resolve each section (full library, My Stuff subset, …).
 */
interface MediaBrowserLookup {
    fun songs(): List<Song>
    fun albums(): List<AlbumItem> = emptyList()
    fun artists(): List<ArtistItem> = emptyList()
    fun playlists(): List<Playlist> = emptyList()
}

/** Convenience when you already have the lists. */
fun mediaBrowserLookup(
    songs: List<Song> = emptyList(),
    albums: List<AlbumItem> = emptyList(),
    artists: List<ArtistItem> = emptyList(),
    playlists: List<Playlist> = emptyList()
): MediaBrowserLookup = object : MediaBrowserLookup {
    override fun songs() = songs
    override fun albums() = albums
    override fun artists() = artists
    override fun playlists() = playlists
}

@Composable
fun MediaBrowser(
    lookup: MediaBrowserLookup,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    sections: List<MediaBrowserSection> = MediaBrowserSection.entries.toList(),
    statusLine: String? = null,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddAlbumToQueue: (List<Song>) -> Unit = {},
    onOpenAlbum: (AlbumItem) -> Unit = {},
    onOpenArtist: (ArtistItem) -> Unit = {},
    onOpenPlaylist: (Playlist) -> Unit = {},
    onEditSong: (Song) -> Unit = {},
    onEditAlbum: (AlbumItem) -> Unit = {},
    onSongClick: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val player: PlayerController = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    // Re-render hearts when collection changes
    val entries by pinStore.entries.collectAsState()

    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var section by remember {
        mutableStateOf(sections.firstOrNull() ?: MediaBrowserSection.Songs)
    }

    val q = query.trim()

    val allSongs = remember(lookup, entries.size) { lookup.songs() }
    val allAlbums = remember(lookup, entries.size) { lookup.albums() }
    val allArtists = remember(lookup, entries.size) { lookup.artists() }
    val allPlaylists = remember(lookup, entries.size) { lookup.playlists() }

    val filteredSongs = remember(allSongs, q, sortMode) {
        allSongs
            .filter {
                q.isEmpty() ||
                    it.displayTitle.contains(q, true) ||
                    it.displayArtist.contains(q, true) ||
                    it.displayAlbum.contains(q, true)
            }
            .let { list ->
                when (sortMode) {
                    SortMode.TITLE -> list.sortedBy { it.displayTitle.lowercase() }
                    SortMode.ARTIST -> list.sortedBy { it.displayArtist.lowercase() }
                    SortMode.ALBUM -> list.sortedBy { it.displayAlbum.lowercase() }
                    SortMode.TRACK -> list.sortedWith(
                        compareBy<Song> { it.discNumber ?: 1 }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy { it.displayTitle.lowercase() }
                    )
                }
            }
    }
    val filteredAlbums = remember(allAlbums, q) {
        allAlbums.filter {
            q.isEmpty() ||
                it.displayName.contains(q, true) ||
                it.displayArtist.contains(q, true)
        }
    }
    val filteredArtists = remember(allArtists, q) {
        allArtists.filter {
            q.isEmpty() || it.displayName.contains(q, true)
        }
    }
    val filteredPlaylists = remember(allPlaylists, q) {
        allPlaylists.filter {
            q.isEmpty() || it.name.contains(q, true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Filter songs, albums, artists…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            )
        )

        if (sections.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { s ->
                    FilterChip(
                        selected = section == s,
                        onClick = { section = s },
                        label = { Text(s.name) }
                    )
                }
            }
        }

        if (section == MediaBrowserSection.Songs) {
            SortDropdown(sortMode) { sortMode = it }
        }

        if (statusLine != null) {
            Text(
                statusLine,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        when (section) {
            MediaBrowserSection.Songs -> {
                if (filteredSongs.isEmpty()) {
                    Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filteredSongs, key = { _, s -> s.songKey }) { index, song ->
                            SwipeAddSongRow(
                                song = song,
                                onClick = {
                                    if (onSongClick != null) onSongClick(song)
                                    else onPlay(filteredSongs, index)
                                },
                                onSwipeAdd = {
                                    onAddToQueue(song)
                                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                },
                                showTrackNumber = false,
                                isPlaying = song.isSameAs(nowPlaying),
                                isPlaybackActive = isPlaybackActive,
                                showHeart = true,
                                onEditMetadata = { onEditSong(song) },
                                onStartRadio = {
                                    player.startSongRadio(song)
                                    Toast.makeText(context, "Radio · ${song.displayArtist}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
            MediaBrowserSection.Albums -> {
                if (filteredAlbums.isEmpty()) {
                    Text("No albums match.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredAlbums, key = { "${it.name}|${it.artist}" }) { album ->
                            SwipeAddAlbumRow(
                                album = album,
                                onClick = { onOpenAlbum(album) },
                                onSwipeAdd = {
                                    onAddAlbumToQueue(album.songs)
                                    Toast.makeText(
                                        context,
                                        "Queued ${formatTrackCount(album.songs.size)}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onEditMetadata = { onEditAlbum(album) },
                                onStartRadio = {
                                    player.startAlbumRadio(album)
                                    Toast.makeText(context, "Radio · ${album.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
            MediaBrowserSection.Artists -> {
                if (filteredArtists.isEmpty()) {
                    Text("No artists match.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredArtists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                            ArtistRow(artist) { onOpenArtist(artist) }
                        }
                    }
                }
            }
            MediaBrowserSection.Playlists -> {
                if (filteredPlaylists.isEmpty()) {
                    Text("No playlists match.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredPlaylists, key = { it.id }) { pl ->
                            PlaylistRow(pl) { onOpenPlaylist(pl) }
                        }
                    }
                }
            }
        }
    }
}
