package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class MyStuffPage {
    Home,
    BrowseAlbums,
    BrowseArtists,
    BrowsePlaylists
}

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
    onOpenPlaylist: (Playlist) -> Unit
) {
    val pinStore: MyStuffPinStore = koinInject()
    val playlistsRepo: PlaylistRepository = koinInject()
    val pins by pinStore.pins.collectAsState()
    val playlists by playlistsRepo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val allSongs by library.songs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var page by remember { mutableStateOf(MyStuffPage.Home) }
    var showAddPin by remember { mutableStateOf(false) }

    BackHandler(enabled = page != MyStuffPage.Home) {
        page = MyStuffPage.Home
    }

    when (page) {
        MyStuffPage.Home -> MyStuffHome(
            pins = pins,
            onBrowseAlbums = { page = MyStuffPage.BrowseAlbums },
            onBrowseArtists = { page = MyStuffPage.BrowseArtists },
            onBrowsePlaylists = { page = MyStuffPage.BrowsePlaylists },
            onOpenPin = { pin ->
                when (pin.kind) {
                    StuffPinKind.ALBUM -> {
                        val album = library.albums(taggedOnly = false)
                            .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                        if (album != null) onOpenAlbum(album)
                        else Toast.makeText(context, "Album not found", Toast.LENGTH_SHORT).show()
                    }
                    StuffPinKind.ARTIST -> {
                        val artist = library.artists(taggedOnly = false)
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
                        val song = allSongs.firstOrNull { it.songKey == pin.id }
                        if (song != null) onPlay(listOf(song), 0)
                        else Toast.makeText(context, "Song not found", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRemovePin = { pinStore.remove(it) },
            onAddPinSlot = { showAddPin = true },
            onPlayAll = {
                scope.launch {
                    val songs = resolvePinnedSongs(pins, library, playlistsRepo)
                    if (songs.isEmpty()) {
                        Toast.makeText(context, "Nothing to play yet — pin some music", Toast.LENGTH_SHORT).show()
                    } else {
                        onPlay(songs, 0)
                    }
                }
            }
        )
        MyStuffPage.BrowseAlbums -> BrowseListScaffold(
            title = "Albums",
            onBack = { page = MyStuffPage.Home }
        ) {
            val albums = remember(allSongs) { library.albums(taggedOnly = false) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(albums, key = { "${it.name}|${it.artist}" }) { album ->
                    AlbumRow(album, onClick = { onOpenAlbum(album) })
                }
            }
        }
        MyStuffPage.BrowseArtists -> BrowseListScaffold(
            title = "Artists",
            onBack = { page = MyStuffPage.Home }
        ) {
            val artists = remember(allSongs) { library.artists(taggedOnly = false) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(artists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                    ArtistRow(artist) { onOpenArtist(artist) }
                }
            }
        }
        MyStuffPage.BrowsePlaylists -> BrowseListScaffold(
            title = "Playlists",
            onBack = { page = MyStuffPage.Home }
        ) {
            if (playlists.isEmpty()) {
                PlaceholderScreen(
                    title = "No playlists yet",
                    body = "Create playlists from song menus or Explore."
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(playlists, key = { it.id }) { pl ->
                        PlaylistRow(pl) { onOpenPlaylist(pl) }
                    }
                }
            }
        }
    }

    if (showAddPin) {
        AddPinSheet(
            library = library,
            playlists = playlists,
            existing = pins,
            onDismiss = { showAddPin = false },
            onPick = { pin ->
                pinStore.add(pin)
                showAddPin = false
                Toast.makeText(context, "Pinned ${pin.title}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun MyStuffHome(
    pins: List<StuffPin>,
    onBrowseAlbums: () -> Unit,
    onBrowseArtists: () -> Unit,
    onBrowsePlaylists: () -> Unit,
    onOpenPin: (StuffPin) -> Unit,
    onRemovePin: (StuffPin) -> Unit,
    onAddPinSlot: () -> Unit,
    onPlayAll: () -> Unit
) {
    val emptySlots = (MyStuffPinStore.GRID_MIN_CELLS -
        MyStuffPinStore.FIXED_BROWSE_COUNT - pins.size).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Stuff",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onPlayAll,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play pinned",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Text(
            text = "Browse All",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "browse-album") {
                BrowsePinCard(
                    label = "Album",
                    icon = Icons.Default.Album,
                    iconShape = RoundedCornerShape(6.dp),
                    onClick = onBrowseAlbums
                )
            }
            item(key = "browse-artist") {
                BrowsePinCard(
                    label = "Artist",
                    icon = Icons.Default.Person,
                    iconShape = CircleShape,
                    onClick = onBrowseArtists
                )
            }
            item(key = "browse-playlist") {
                BrowsePinCard(
                    label = "Playlist",
                    icon = Icons.Default.QueueMusic,
                    iconShape = RoundedCornerShape(10.dp),
                    onClick = onBrowsePlaylists
                )
            }
            items(pins, key = { "${it.kind}:${it.id}" }) { pin ->
                UserPinCard(
                    pin = pin,
                    onClick = { onOpenPin(pin) },
                    onLongClickRemove = { onRemovePin(pin) }
                )
            }
            items(emptySlots, key = { "empty-$it" }) {
                EmptyPinCard(onClick = onAddPinSlot)
            }
        }
    }
}

@Composable
private fun BrowsePinCard(
    label: String,
    icon: ImageVector,
    iconShape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(iconShape)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), iconShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UserPinCard(
    pin: StuffPin,
    onClick: () -> Unit,
    onLongClickRemove: () -> Unit
) {
    val icon = when (pin.kind) {
        StuffPinKind.ALBUM -> Icons.Default.Album
        StuffPinKind.ARTIST -> Icons.Default.Person
        StuffPinKind.PLAYLIST -> Icons.Default.QueueMusic
        StuffPinKind.SONG -> Icons.Default.MusicNote
    }
    val shape = when (pin.kind) {
        StuffPinKind.ARTIST -> CircleShape
        StuffPinKind.PLAYLIST -> RoundedCornerShape(10.dp)
        else -> RoundedCornerShape(6.dp)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pin.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (pin.subtitle.isNotBlank()) {
                Text(
                    text = pin.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onLongClickRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Unpin",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun EmptyPinCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "Add pin",
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun BrowseListScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPinSheet(
    library: LibraryIndex,
    playlists: List<Playlist>,
    existing: List<StuffPin>,
    onDismiss: () -> Unit,
    onPick: (StuffPin) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val allSongs by library.songs.collectAsState()

    val existingKeys = remember(existing) {
        existing.map { "${it.kind}:${it.id}" }.toSet()
    }

    val artistHits = remember(allSongs, q) {
        library.artists(query = q, taggedOnly = false)
            .filter { artistKey(it.name)?.let { k -> "${StuffPinKind.ARTIST}:$k" !in existingKeys } != false }
            .take(20)
    }
    val playlistHits = remember(playlists, q) {
        playlists
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .filter { "${StuffPinKind.PLAYLIST}:${it.id}" !in existingKeys }
            .take(20)
    }
    val songHits = remember(allSongs, q) {
        if (q.isEmpty()) emptyList()
        else library.search(q).filter { "${StuffPinKind.SONG}:${it.songKey}" !in existingKeys }.take(30)
    }
    val albumHits = remember(allSongs, q) {
        if (q.isEmpty()) emptyList()
        else library.albums(query = q, taggedOnly = false)
            .filter { "${StuffPinKind.ALBUM}:${albumKey(it.name, it.artist)}" !in existingKeys }
            .take(15)
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
                placeholder = { Text("Search artists, playlists, songs…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                if (artistHits.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artistHits, key = { "a-${it.name}" }) { artist ->
                        val key = artistKey(artist.name) ?: return@items
                        PinSearchRow(
                            title = artist.displayName,
                            subtitle = formatTrackCount(artist.trackCount),
                            icon = Icons.Default.Person,
                            onClick = {
                                onPick(
                                    StuffPin(
                                        kind = StuffPinKind.ARTIST,
                                        id = key,
                                        title = artist.displayName,
                                        subtitle = formatTrackCount(artist.trackCount)
                                    )
                                )
                            }
                        )
                    }
                }
                if (playlistHits.isNotEmpty()) {
                    item { SectionHeader("Playlists") }
                    items(playlistHits, key = { "p-${it.id}" }) { pl ->
                        PinSearchRow(
                            title = pl.name,
                            subtitle = "Playlist",
                            icon = Icons.Default.QueueMusic,
                            onClick = {
                                onPick(
                                    StuffPin(
                                        kind = StuffPinKind.PLAYLIST,
                                        id = pl.id,
                                        title = pl.name,
                                        subtitle = "Playlist"
                                    )
                                )
                            }
                        )
                    }
                }
                if (albumHits.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(albumHits, key = { "al-${it.name}|${it.artist}" }) { album ->
                        PinSearchRow(
                            title = album.displayName,
                            subtitle = album.displayArtist,
                            icon = Icons.Default.Album,
                            onClick = {
                                onPick(
                                    StuffPin(
                                        kind = StuffPinKind.ALBUM,
                                        id = albumKey(album.name, album.artist),
                                        title = album.displayName,
                                        subtitle = album.displayArtist
                                    )
                                )
                            }
                        )
                    }
                }
                if (songHits.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    items(songHits, key = { "s-${it.songKey}" }) { song ->
                        PinSearchRow(
                            title = song.displayTitle,
                            subtitle = song.displayArtist,
                            icon = Icons.Default.MusicNote,
                            onClick = {
                                onPick(
                                    StuffPin(
                                        kind = StuffPinKind.SONG,
                                        id = song.songKey,
                                        title = song.displayTitle,
                                        subtitle = song.displayArtist
                                    )
                                )
                            }
                        )
                    }
                }
                if (q.isNotEmpty() &&
                    artistHits.isEmpty() &&
                    playlistHits.isEmpty() &&
                    albumHits.isEmpty() &&
                    songHits.isEmpty()
                ) {
                    item {
                        Text(
                            "No matches",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
                if (q.isEmpty() && artistHits.isEmpty() && playlistHits.isEmpty()) {
                    item {
                        Text(
                            "Type to search your library",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun PinSearchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.Add, contentDescription = "Pin")
    }
}

private suspend fun resolvePinnedSongs(
    pins: List<StuffPin>,
    library: LibraryIndex,
    playlistsRepo: PlaylistRepository
): List<Song> {
    val out = ArrayList<Song>()
    val seen = HashSet<String>()
    fun addAll(songs: List<Song>) {
        songs.forEach { s ->
            val k = s.songKey
            if (seen.add(k)) out.add(s)
        }
    }
    for (pin in pins) {
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
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QueueMusic, contentDescription = null)
        }
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
