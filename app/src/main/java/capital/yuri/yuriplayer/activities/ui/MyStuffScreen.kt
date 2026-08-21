package capital.yuri.yuriplayer.activities.ui

import MarqueeText
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class MyStuffTab { Pins, Collection, Playlists }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyStuffScreen(
    library: LibraryIndex,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit
) {
    val pinStore: MyStuffPinStore = koinInject()
    val playlistsRepo: PlaylistRepository = koinInject()
    val catalog: CatalogRepository = koinInject()
    val player: PlayerController = koinInject()
    val pins by pinStore.pins.collectAsState()
    val entries by pinStore.entries.collectAsState()
    val playlists by playlistsRepo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val allSongs by library.songs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(MyStuffTab.Pins) }
    var showAddPin by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(playlistsRepo) {
        playlistsRepo.observePlaylistsResolved().collect { list ->
            pinStore.pruneMissingPlaylists(list.map { it.id }.toSet())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                MyStuffTab.Pins -> MyStuffPinsHost(
                    pins = pins,
                    library = library,
                    playlists = playlists,
                    allSongs = allSongs,
                    onOpenPin = { pin ->
                        scope.launch {
                            openPin(
                                pin, library, playlists, allSongs, catalog,
                                onOpenAlbum, onOpenArtist, onOpenPlaylist, onOpenSongAlbum, context
                            )
                        }
                    },
                    onUnpin = { pinStore.unpin(it) },
                    onAddPinSlot = { showAddPin = true },
                    onPlayAll = {
                        scope.launch {
                            val songs = resolveCollectionSongs(entries, library, playlistsRepo)
                            if (songs.isEmpty()) {
                                Toast.makeText(context, "Nothing in My Stuff yet", Toast.LENGTH_SHORT).show()
                            } else {
                                player.startPlaylistRadio(songs, "My Stuff")
                                Toast.makeText(context, "Radio · My Stuff", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                MyStuffTab.Collection -> MyStuffCatalogTab(
                    entries = entries.filter { it.kind != StuffPinKind.PLAYLIST },
                    library = library,
                    playlists = playlists,
                    nowPlaying = nowPlaying,
                    isPlaybackActive = isPlaybackActive,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSongAlbum = onOpenSongAlbum,
                    onPlay = onPlay,
                    onAddToQueue = onAddToQueue
                )
                MyStuffTab.Playlists -> MyStuffPlaylistsList(
                    playlists = playlists,
                    onOpen = onOpenPlaylist,
                    onCreate = { showCreatePlaylist = true }
                )
            }
        }

        NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
            NavigationBarItem(
                selected = tab == MyStuffTab.Pins,
                onClick = { tab = MyStuffTab.Pins },
                icon = {
                    Icon(
                        if (tab == MyStuffTab.Pins) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null
                    )
                },
                label = { Text("Pins") }
            )
            NavigationBarItem(
                selected = tab == MyStuffTab.Collection,
                onClick = { tab = MyStuffTab.Collection },
                icon = {
                    Icon(
                        if (tab == MyStuffTab.Collection) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null
                    )
                },
                label = { Text("Catalog") }
            )
            NavigationBarItem(
                selected = tab == MyStuffTab.Playlists,
                onClick = { tab = MyStuffTab.Playlists },
                icon = {
                    Icon(
                        if (tab == MyStuffTab.Playlists) Icons.Filled.QueueMusic else Icons.Outlined.QueueMusic,
                        contentDescription = null
                    )
                },
                label = { Text("Playlists") }
            )
        }
    }

    if (showAddPin) {
        AddPinFromCollectionSheet(
            entries = entries,
            playlists = playlists,
            alreadyPinned = pins.map { it.key }.toSet(),
            library = library,
            allSongs = allSongs,
            onDismiss = { showAddPin = false },
            onPick = { pin ->
                pinStore.pin(pin)
                showAddPin = false
                Toast.makeText(context, "Pinned ${pin.title}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showCreatePlaylist) {
        CreatePlaylistSheet(
            onDismiss = { showCreatePlaylist = false },
            onCreated = {
                showCreatePlaylist = false
                tab = MyStuffTab.Playlists
            }
        )
    }
}

@Composable
private fun MyStuffCollectionTab(
    entries: List<StuffPin>,
    library: LibraryIndex,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onPlaySong: (Song) -> Unit,
    onRemoveEntry: (StuffPin) -> Unit
) {
    val albums = entries.filter { it.kind == StuffPinKind.ALBUM }
    val artists = entries.filter { it.kind == StuffPinKind.ARTIST }
    val songs = entries.filter { it.kind == StuffPinKind.SONG }
    val allSongs by library.songs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Collection",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        if (entries.isEmpty()) {
            PlaceholderScreen(
                title = "Nothing saved yet",
                body = "Heart albums, artists, or songs to add them here."
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (artists.isNotEmpty()) {
                item { SectionLabel("Artists") }
                items(artists, key = { it.key }) { pin ->
                    BrowseEntryRow(
                        pin = pin,
                        leading = {
                            ArtistArt(artistName = pin.title, size = 48.dp, circular = true)
                        },
                        onClick = {
                            val artist = library.artists(taggedOnly = false)
                                .firstOrNull { artistKey(it.name) == pin.id }
                            if (artist != null) onOpenArtist(artist)
                        },
                        onRemove = { onRemoveEntry(pin) }
                    )
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionLabel("Albums") }
                items(albums, key = { it.key }) { pin ->
                    val album = library.albums(taggedOnly = false)
                        .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                    BrowseEntryRow(
                        pin = pin,
                        leading = {
                            AlbumArt(song = album?.songs?.firstOrNull(), size = 48.dp, corner = 4.dp)
                        },
                        onClick = { if (album != null) onOpenAlbum(album) },
                        onRemove = { onRemoveEntry(pin) }
                    )
                }
            }
            if (songs.isNotEmpty()) {
                item { SectionLabel("Songs") }
                items(songs, key = { it.key }) { pin ->
                    val song = allSongs.firstOrNull { it.songKey == pin.id }
                    BrowseEntryRow(
                        pin = pin,
                        leading = {
                            AlbumArt(song = song, size = 48.dp, corner = 4.dp)
                        },
                        onClick = { if (song != null) onPlaySong(song) },
                        onRemove = { onRemoveEntry(pin) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PinLeadingArt(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    fill: Boolean = false
) {
    val shape: Shape = when (pin.kind) {
        StuffPinKind.ARTIST -> CircleShape
        StuffPinKind.PLAYLIST -> RoundedCornerShape(12.dp)
        StuffPinKind.ALBUM, StuffPinKind.SONG -> RoundedCornerShape(8.dp)
    }
    val fallback: ImageVector = when (pin.kind) {
        StuffPinKind.ALBUM -> Icons.Default.Album
        StuffPinKind.ARTIST -> Icons.Default.Person
        StuffPinKind.PLAYLIST -> Icons.Default.QueueMusic
        StuffPinKind.SONG -> Icons.Default.MusicNote
    }

    val boxMod = if (fill) {
        Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    } else {
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    }

    Box(modifier = boxMod, contentAlignment = Alignment.Center) {
        val artSize = size
        when (pin.kind) {
            StuffPinKind.ALBUM -> {
                val album = remember(pin.id, library) {
                    library.albums(taggedOnly = false)
                        .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                }
                if (album != null) {
                    AlbumArt(song = album.songs.firstOrNull(), size = if (fill) 120.dp else artSize, corner = 8.dp)
                } else {
                    Icon(fallback, null, Modifier.size((if (fill) 48.dp else artSize) * 0.55f))
                }
            }
            StuffPinKind.ARTIST -> {
                val seed = remember(pin.id, library) {
                    library.artists(taggedOnly = false)
                        .firstOrNull { artistKey(it.name) == pin.id }
                        ?.songs?.firstOrNull()
                }
                ArtistArt(artistName = pin.title, seedSong = seed, size = if (fill) 120.dp else artSize, circular = true)
            }
            StuffPinKind.PLAYLIST -> {
                val pl = playlists.firstOrNull { it.id == pin.id }
                if (pl != null) {
                    PlaylistCoverArt(pl, size = if (fill) 120.dp else artSize)
                } else {
                    Icon(fallback, null, Modifier.size((if (fill) 48.dp else artSize) * 0.55f))
                }
            }
            StuffPinKind.SONG -> {
                val song = allSongs.firstOrNull { it.songKey == pin.id }
                if (song != null) {
                    AlbumArt(song = song, size = if (fill) 120.dp else artSize, corner = 8.dp)
                } else {
                    Icon(fallback, null, Modifier.size((if (fill) 48.dp else artSize) * 0.55f))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun BrowseEntryRow(
    pin: StuffPin,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(text = pin.title, style = MaterialTheme.typography.bodyLarge)
            if (pin.subtitle.isNotBlank()) {
                Text(
                    pin.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove from My Stuff",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPinFromCollectionSheet(
    entries: List<StuffPin>,
    playlists: List<Playlist>,
    alreadyPinned: Set<String>,
    library: LibraryIndex,
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onPick: (StuffPin) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()

    val candidates = remember(entries, playlists, alreadyPinned, q) {
        val fromEntries = entries.filter { it.key !in alreadyPinned }
        val fromPlaylists = playlists
            .map {
                StuffPin(
                    kind = StuffPinKind.PLAYLIST,
                    id = it.id,
                    title = it.name,
                    subtitle = "Playlist"
                )
            }
            .filter { it.key !in alreadyPinned }
        (fromEntries + fromPlaylists)
            .distinctBy { it.key }
            .filter {
                q.isEmpty() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.subtitle.contains(q, ignoreCase = true)
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Add pin",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (candidates.isEmpty()) {
                Text(
                    if (alreadyPinned.size >= MyStuffPinStore.PIN_SLOTS) "All slots filled"
                    else "Nothing to pin yet",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    items(candidates, key = { it.key }) { pin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pin) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PinLeadingArt(pin, library, playlists, allSongs, size = 40.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                MarqueeText(
                                    text = pin.title,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    pin.subtitle.ifBlank {
                                        pin.kind.name.lowercase().replaceFirstChar { it.titlecase() }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            Icon(Icons.Default.Add, contentDescription = "Pin")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun openPin(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    catalog: CatalogRepository,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit,
    context: android.content.Context
) {
    when (pin.kind) {
        StuffPinKind.ALBUM -> {
            val album = catalog.albumItemForKey(pin.id)
                ?: library.albums(taggedOnly = false)
                    .firstOrNull { albumKey(it.name, it.artist) == pin.id }
            if (album != null) onOpenAlbum(album)
            else Toast.makeText(context, "Album not found", Toast.LENGTH_SHORT).show()
        }
        StuffPinKind.ARTIST -> {
            val artist = catalog.artistItemForKey(pin.id, pin.title)
                ?: library.artists(taggedOnly = false)
                    .firstOrNull { artistKey(it.name) == pin.id }
            if (artist != null) onOpenArtist(artist)
            else Toast.makeText(context, "Artist not found", Toast.LENGTH_SHORT).show()
        }
        StuffPinKind.PLAYLIST -> {
            val pl = playlists.firstOrNull { it.id == pin.id }
            if (pl != null) onOpenPlaylist(pl)
            else Toast.makeText(context, "Playlist not found", Toast.LENGTH_SHORT).show()
        }
        StuffPinKind.SONG -> {
            val song = catalog.getSongsByKeys(listOf(pin.id)).firstOrNull()
                ?: allSongs.firstOrNull { it.songKey == pin.id }
            if (song != null) onOpenSongAlbum(song)
            else Toast.makeText(context, "Song not found", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun resolveCollectionSongs(
    entries: List<StuffPin>,
    library: LibraryIndex,
    playlistsRepo: PlaylistRepository
): List<Song> {
    val out = ArrayList<Song>()
    val seen = HashSet<String>()
    fun addAll(songs: List<Song>) {
        songs.forEach { s ->
            if (seen.add(s.songKey)) out.add(s)
        }
    }
    for (pin in entries) {
        when (pin.kind) {
            StuffPinKind.SONG -> {
                library.songs.value.firstOrNull { it.songKey == pin.id }?.let { addAll(listOf(it)) }
            }
            StuffPinKind.ARTIST -> {
                library.artists(taggedOnly = false)
                    .firstOrNull { artistKey(it.name) == pin.id }
                    ?.let { addAll(it.songs) }
            }
            StuffPinKind.ALBUM -> {
                library.albums(taggedOnly = false)
                    .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                    ?.let { addAll(it.songs) }
            }
            StuffPinKind.PLAYLIST -> {
                val pl = playlistsRepo.observePlaylist(pin.id).first()
                if (pl != null) addAll(pl.songs)
            }
        }
    }
    return out
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
