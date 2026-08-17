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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Shape
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
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class MyStuffPage { Home, BrowseAll }

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
    val player: PlayerController = koinInject()
    val pins by pinStore.pins.collectAsState()
    val entries by pinStore.entries.collectAsState()
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
            onBrowseAll = { page = MyStuffPage.BrowseAll },
            onOpenPin = { pin -> openPin(pin, library, playlists, allSongs, onOpenAlbum, onOpenArtist, onOpenPlaylist, onPlay, context) },
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
        MyStuffPage.BrowseAll -> BrowseMyStuff(
            entries = entries,
            library = library,
            playlists = playlists,
            onBack = { page = MyStuffPage.Home },
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onOpenPlaylist = onOpenPlaylist,
            onPlaySong = { song -> onPlay(listOf(song), 0) },
            onRemoveEntry = { pinStore.removeEntry(it) }
        )
    }

    if (showAddPin) {
        AddPinFromCollectionSheet(
            entries = entries,
            alreadyPinned = pins.map { it.key }.toSet(),
            onDismiss = { showAddPin = false },
            onPick = { pin ->
                pinStore.pin(pin)
                showAddPin = false
                Toast.makeText(context, "Pinned ${pin.title}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun MyStuffHome(
    pins: List<StuffPin>,
    onBrowseAll: () -> Unit,
    onOpenPin: (StuffPin) -> Unit,
    onUnpin: (StuffPin) -> Unit,
    onAddPinSlot: () -> Unit,
    onPlayAll: () -> Unit
) {
    val emptyCount = (MyStuffPinStore.PIN_SLOTS - pins.size).coerceAtLeast(0)
    // Build 10 slots: pins then empties, render as rows of 2
    val slots: List<StuffPin?> = pins + List(emptyCount) { null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
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
                    contentDescription = "Start radio from My Stuff",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        TextButton(
            onClick = onBrowseAll,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        ) {
            Text(
                "Browse All",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Rows of 2
        slots.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { pin ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (pin != null) {
                            UserPinCard(
                                pin = pin,
                                onClick = { onOpenPin(pin) },
                                onUnpin = { onUnpin(pin) }
                            )
                        } else {
                            EmptyPinCard(onClick = onAddPinSlot)
                        }
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UserPinCard(
    pin: StuffPin,
    onClick: () -> Unit,
    onUnpin: () -> Unit
) {
    val shape: Shape = when (pin.kind) {
        StuffPinKind.ARTIST -> CircleShape
        StuffPinKind.PLAYLIST -> RoundedCornerShape(10.dp)
        StuffPinKind.ALBUM, StuffPinKind.SONG -> RoundedCornerShape(6.dp)
    }
    val icon: ImageVector = when (pin.kind) {
        StuffPinKind.ALBUM -> Icons.Default.Album
        StuffPinKind.ARTIST -> Icons.Default.Person
        StuffPinKind.PLAYLIST -> Icons.Default.QueueMusic
        StuffPinKind.SONG -> Icons.Default.MusicNote
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when (pin.kind) {
                StuffPinKind.ARTIST -> Icon(icon, null, Modifier.size(18.dp))
                else -> Icon(icon, null, Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
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
        IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Unpin",
                modifier = Modifier.size(14.dp),
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
private fun BrowseMyStuff(
    entries: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlaySong: (Song) -> Unit,
    onRemoveEntry: (StuffPin) -> Unit
) {
    val albums = entries.filter { it.kind == StuffPinKind.ALBUM }
    val artists = entries.filter { it.kind == StuffPinKind.ARTIST }
    val pls = entries.filter { it.kind == StuffPinKind.PLAYLIST }
    val songs = entries.filter { it.kind == StuffPinKind.SONG }
    val allSongs by library.songs.collectAsState()

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
                text = "Browse All",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (entries.isEmpty()) {
            PlaceholderScreen(
                title = "Nothing saved yet",
                body = "Heart albums, artists, playlists, or songs to add them here."
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
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
            if (pls.isNotEmpty()) {
                item { SectionLabel("Playlists") }
                items(pls, key = { it.key }) { pin ->
                    val pl = playlists.firstOrNull { it.id == pin.id }
                    BrowseEntryRow(
                        pin = pin,
                        leading = {
                            if (pl != null) PlaylistCoverArt(pl, size = 48.dp)
                            else Icon(Icons.Default.QueueMusic, null)
                        },
                        onClick = { if (pl != null) onOpenPlaylist(pl) },
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
            Text(pin.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    alreadyPinned: Set<String>,
    onDismiss: () -> Unit,
    onPick: (StuffPin) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val candidates = remember(entries, alreadyPinned, q) {
        entries
            .filter { it.key !in alreadyPinned }
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
                placeholder = { Text("Search My Stuff…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Text(
                    "Save music to My Stuff first (heart / Add to My Stuff), then pin it here.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else if (candidates.isEmpty()) {
                Text(
                    if (alreadyPinned.size >= MyStuffPinStore.PIN_SLOTS) "All slots filled"
                    else "No matches",
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
                        val icon = when (pin.kind) {
                            StuffPinKind.ALBUM -> Icons.Default.Album
                            StuffPinKind.ARTIST -> Icons.Default.Person
                            StuffPinKind.PLAYLIST -> Icons.Default.QueueMusic
                            StuffPinKind.SONG -> Icons.Default.MusicNote
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pin) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(
                                        when (pin.kind) {
                                            StuffPinKind.ARTIST -> CircleShape
                                            StuffPinKind.PLAYLIST -> RoundedCornerShape(10.dp)
                                            else -> RoundedCornerShape(6.dp)
                                        }
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pin.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pin.subtitle.ifBlank { pin.kind.name.lowercase().replaceFirstChar { it.titlecase() } },
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

private fun openPin(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    context: android.content.Context
) {
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
