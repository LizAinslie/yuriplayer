package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.allCreditsForSong
import capital.yuri.yuriplayer.data.isCombinedArtistName
import capital.yuri.yuriplayer.data.primaryArtistName
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.player.PlayerController
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun MediaSheetHeader(
    song: Song?,
    title: String,
    subtitle: String,
    artSize: Dp = 56.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = song, size = artSize, corner = 6.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun PlaylistSheetHeader(
    playlist: Playlist,
    title: String,
    subtitle: String,
    artSize: Dp = 56.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistCoverArt(playlist, size = artSize)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun ArtistSheetHeader(
    artistName: String,
    title: String,
    subtitle: String,
    artSize: Dp = 56.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistArt(
            artistName = artistName,
            seedSong = null,
            size = artSize,
            circular = true
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun MediaSheetItem(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun MediaSheetBottomPad() {
    Spacer(modifier = Modifier.height(28.dp))
}

private fun songArtistNames(song: Song): List<String> {
    val credits = allCreditsForSong(song)
        .map { it.name.trim() }
        .filter { it.isNotEmpty() && !isCombinedArtistName(it) }
    if (credits.isNotEmpty()) return credits.distinctBy { it.lowercase() }
    val single = primaryArtistName(song.effectiveAlbumArtist)
        ?: primaryArtistName(song.artist)
        ?: song.primaryArtist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
    return listOfNotNull(single?.takeUnless { isCombinedArtistName(it) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoToArtistSheet(
    songTitle: String,
    artists: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Text(
            "Go to artist",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Text(
            songTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        artists.forEach { name ->
            MediaSheetItem(name) { onPick(name) }
        }
        MediaSheetBottomPad()
    }
}

/** Shared song sheet — Sources is always available; multi badge is separate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongContextSheet(
    song: Song,
    onDismiss: () -> Unit,
    hideGoToAlbum: Boolean = false,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddToMyStuff: (() -> Unit)? = null,
    sourceOfferings: List<SourceOffering>? = null,
    onPreferSource: ((SourceOffering) -> Unit)? = null
) {
    val pinStore: MyStuffPinStore = koinInject()
    val player: PlayerController = koinInject()
    val context = LocalContext.current
    val songNav = LocalSongNav.current
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showArtistPicker by remember { mutableStateOf(false) }
    var showSourcesPicker by remember { mutableStateOf(false) }
    val artists = remember(song) { songArtistNames(song) }
    val entries by pinStore.entries.collectAsState()
    val inMyStuff = remember(entries, song.songKey) {
        pinStore.contains(StuffPinKind.SONG, song.songKey)
    }

    // Always resolve so album/playlist/discography long-press works without parents.
    val offerings = rememberSongOfferings(song, sourceOfferings)
    val preferHandler = onPreferSource ?: rememberPreferSourceHandler(song)

    val goToAlbum = onGoToAlbum ?: { songNav.openAlbumForSong(song) }
    val goToArtist = onGoToArtist ?: { name -> songNav.openArtistByName(name) }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            songs = listOf(song),
            onDismiss = {
                showPlaylistPicker = false
                onDismiss()
            }
        )
        return
    }

    if (showArtistPicker) {
        GoToArtistSheet(
            songTitle = song.displayTitle,
            artists = artists,
            onPick = { name ->
                showArtistPicker = false
                onDismiss()
                goToArtist(name)
            },
            onDismiss = {
                showArtistPicker = false
                onDismiss()
            }
        )
        return
    }

    if (showSourcesPicker) {
        SourcesPickerSheet(
            songTitle = song.displayTitle,
            offerings = offerings,
            preferred = offerings.firstOrNull(),
            onDismiss = {
                showSourcesPicker = false
                onDismiss()
            },
            onPick = { off ->
                preferHandler(off)
                showSourcesPicker = false
                onDismiss()
            }
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        MediaSheetHeader(
            song = song,
            title = song.displayTitle,
            subtitle = "${song.displayArtist} · ${song.displayAlbum}"
        )
        MediaSheetItem("Start radio") {
            onDismiss()
            if (onStartRadio != null) onStartRadio()
            else {
                player.startSongRadio(song)
                Toast.makeText(context, "Radio · ${song.displayArtist}", Toast.LENGTH_SHORT).show()
            }
        }
        MediaSheetItem("Add to queue") {
            onDismiss()
            if (onAddToQueue != null) onAddToQueue()
            else Toast.makeText(context, "No queue handler", Toast.LENGTH_SHORT).show()
        }
        MediaSheetItem("Add to playlist") {
            if (onAddToPlaylist != null) {
                onDismiss()
                onAddToPlaylist()
            } else {
                showPlaylistPicker = true
            }
        }
        MediaSheetItem(if (inMyStuff) "Remove from My Stuff" else "Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) {
                onAddToMyStuff()
            } else {
                val added = pinStore.toggleSong(song)
                Toast.makeText(
                    context,
                    if (added) "Added to My Stuff" else "Removed from My Stuff",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (!hideGoToAlbum && !song.album.isNullOrBlank()) {
            MediaSheetItem("Go to album") {
                onDismiss()
                goToAlbum()
            }
        }
        if (artists.isNotEmpty()) {
            MediaSheetItem("Go to artist") {
                if (artists.size == 1) {
                    onDismiss()
                    goToArtist(artists.first())
                } else {
                    showArtistPicker = true
                }
            }
        }
        // Always available — even with a single local/Jellyfin source.
        MediaSheetItem("Sources") {
            showSourcesPicker = true
        }
        if (onEditMetadata != null) {
            MediaSheetItem("Edit metadata") {
                onDismiss()
                onEditMetadata()
            }
        }
        MediaSheetBottomPad()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumContextSheet(
    album: AlbumItem,
    onDismiss: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onFetchMetadata: (() -> Unit)? = null,
    onAddToMyStuff: (() -> Unit)? = null
) {
    val albumNav = LocalAlbumNav.current
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val entries by pinStore.entries.collectAsState()
    val aKey = remember(album.name, album.artist) { albumKey(album.name, album.artist) }
    val inMyStuff = remember(entries, aKey) {
        pinStore.contains(StuffPinKind.ALBUM, aKey)
    }

    val goToArtist = onGoToArtist ?: { albumNav.openArtist(album) }
    val editMeta = onEditMetadata ?: { albumNav.editMetadata(album) }
    val addQueue = onAddToQueue ?: { albumNav.addToQueue(album) }
    val startRadio = onStartRadio ?: { albumNav.startRadio(album) }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            songs = album.songs,
            onDismiss = { showPlaylistPicker = false }
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        MediaSheetHeader(
            song = album.songs.firstOrNull(),
            title = album.displayName,
            subtitle = album.displayArtist
        )
        MediaSheetItem("Start radio") {
            onDismiss()
            startRadio()
        }
        MediaSheetItem("Add to queue") {
            onDismiss()
            addQueue()
        }
        MediaSheetItem("Add to playlist") {
            showPlaylistPicker = true
        }
        MediaSheetItem(if (inMyStuff) "Remove from My Stuff" else "Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) {
                onAddToMyStuff()
            } else {
                val added = pinStore.toggleAlbum(album)
                Toast.makeText(
                    context,
                    if (added) "Added to My Stuff" else "Removed from My Stuff",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (album.artist?.isNotBlank() == true) {
            MediaSheetItem("Go to artist") {
                onDismiss()
                goToArtist()
            }
        }
        MediaSheetItem("Edit album metadata") {
            onDismiss()
            editMeta()
        }
        if (onFetchMetadata != null) {
            MediaSheetItem("Fetch additional metadata") {
                onDismiss()
                onFetchMetadata()
            }
        }
        MediaSheetBottomPad()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistContextSheet(
    artist: ArtistItem,
    onDismiss: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onChangeImage: (() -> Unit)? = null,
    onFetchImage: (() -> Unit)? = null,
    onChangeBanner: (() -> Unit)? = null,
    onFetchBanner: (() -> Unit)? = null,
    onClearImage: (() -> Unit)? = null,
    onClearBanner: (() -> Unit)? = null,
    onOpenLinks: (() -> Unit)? = null,
    onAddToMyStuff: (() -> Unit)? = null,
    onMerge: (() -> Unit)? = null
) {
    val artistNav = LocalArtistNav.current
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current
    val name = artist.displayName
    val entries by pinStore.entries.collectAsState()
    val aKey = remember(artist.name, artist.displayName) {
        artistKey(artist.name) ?: artist.displayName.lowercase()
    }
    val inMyStuff = remember(entries, aKey) {
        pinStore.contains(StuffPinKind.ARTIST, aKey)
    }

    val startRadio = onStartRadio ?: { artistNav.startRadio(name) }
    val changeImage = onChangeImage ?: artistNav.changeImage?.let { fn -> { fn(name) } }
    val fetchImage = onFetchImage ?: artistNav.fetchImage?.let { fn -> { fn(name) } }
    val changeBanner = onChangeBanner ?: artistNav.changeBanner?.let { fn -> { fn(name) } }
    val fetchBanner = onFetchBanner ?: artistNav.fetchBanner?.let { fn -> { fn(name) } }
    val clearImage = onClearImage ?: artistNav.clearImage?.let { fn -> { fn(name) } }
    val clearBanner = onClearBanner ?: artistNav.clearBanner?.let { fn -> { fn(name) } }
    val openLinks = onOpenLinks ?: artistNav.openLinks?.let { fn -> { fn(name) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        ArtistSheetHeader(
            artistName = artist.name ?: artist.displayName,
            title = artist.displayName,
            subtitle = "Artist"
        )
        MediaSheetItem("Start radio") {
            onDismiss()
            startRadio()
        }
        MediaSheetItem(if (inMyStuff) "Remove from My Stuff" else "Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) {
                onAddToMyStuff()
            } else {
                val added = pinStore.toggleArtist(artist)
                Toast.makeText(
                    context,
                    if (added) "Added to My Stuff" else "Removed from My Stuff",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (fetchImage != null) {
            MediaSheetItem("Fetch artist image") {
                onDismiss()
                fetchImage()
            }
        }
        if (changeImage != null) {
            MediaSheetItem("Change artist image") {
                onDismiss()
                changeImage()
            }
        }
        if (clearImage != null) {
            MediaSheetItem("Clear artist image") {
                onDismiss()
                clearImage()
            }
        }
        if (fetchBanner != null) {
            MediaSheetItem("Fetch banner") {
                onDismiss()
                fetchBanner()
            }
        }
        if (changeBanner != null) {
            MediaSheetItem("Change banner") {
                onDismiss()
                changeBanner()
            }
        }
        if (clearBanner != null) {
            MediaSheetItem("Clear banner") {
                onDismiss()
                clearBanner()
            }
        }
        if (openLinks != null) {
            MediaSheetItem("Links") {
                onDismiss()
                openLinks()
            }
        }
        if (onMerge != null) {
            MediaSheetItem("Merge other artists into this page") {
                onDismiss()
                onMerge()
            }
        }
        MediaSheetBottomPad()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistContextSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onChangeCover: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onAddToMyStuff: (() -> Unit)? = null
) {
    val playlistNav = LocalPlaylistNav.current
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current
    val entries by pinStore.entries.collectAsState()
    val inMyStuff = remember(entries, playlist.id) {
        pinStore.contains(StuffPinKind.PLAYLIST, playlist.id)
    }

    val startRadio = onStartRadio ?: { playlistNav.startRadio(playlist) }
    val changeCover = onChangeCover ?: playlistNav.changeCover?.let { fn -> { fn(playlist.id) } }
    val edit = onEdit ?: playlistNav.edit?.let { fn -> { fn(playlist.id) } }
    val delete = onDelete ?: playlistNav.delete?.let { fn -> { fn(playlist.id) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        PlaylistSheetHeader(
            playlist = playlist,
            title = playlist.name,
            subtitle = "Playlist"
        )
        MediaSheetItem("Start radio") {
            onDismiss()
            startRadio()
        }
        MediaSheetItem(if (inMyStuff) "Remove from My Stuff" else "Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) {
                onAddToMyStuff()
            } else {
                val pin = StuffPin(
                    kind = StuffPinKind.PLAYLIST,
                    id = playlist.id,
                    title = playlist.name,
                    subtitle = "Playlist"
                )
                val added = pinStore.toggleEntry(pin)
                Toast.makeText(
                    context,
                    if (added) "Added to My Stuff" else "Removed from My Stuff",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (changeCover != null) {
            MediaSheetItem("Change cover") {
                onDismiss()
                changeCover()
            }
        }
        if (edit != null) {
            MediaSheetItem("Edit playlist") {
                onDismiss()
                edit()
            }
        }
        if (delete != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MediaSheetItem("Delete playlist", danger = true) {
                onDismiss()
                delete()
            }
        }
        MediaSheetBottomPad()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    songs: List<Song>,
    onDismiss: () -> Unit
) {
    val repo: PlaylistRepository = koinInject()
    val playlists by repo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var initiallyIn by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showCreate by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(songs.map { it.songKey }) {
        val containing = repo.playlistsContaining(songs)
        initiallyIn = containing
        selected = containing
        ready = true
    }

    val visiblePlaylists = remember(playlists, query, selected) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            playlists
        } else {
            playlists.filter { it.name.contains(q, ignoreCase = true) }
        }
        filtered.sortedWith(
            compareByDescending<Playlist> { it.id in selected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    if (showCreate) {
        CreatePlaylistSheet(
            onDismiss = { showCreate = false },
            onCreated = { pl ->
                selected = selected + pl.id
                showCreate = false
            }
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                if (songs.size == 1) songs.first().displayTitle
                else "${songs.size} tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedButton(
                onClick = { showCreate = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("New playlist")
            }

            if (playlists.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    placeholder = { Text("Search playlists") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet — create one above.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else if (visiblePlaylists.isEmpty()) {
                Text(
                    "No playlists match \"$query\".",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(visiblePlaylists, key = { it.id }) { pl ->
                        val checked = pl.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - pl.id else selected + pl.id
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (it) selected + pl.id else selected - pl.id
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            PlaylistCoverArt(pl, size = 40.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        val toAdd = selected - initiallyIn
                        val toRemove = initiallyIn - selected
                        toAdd.forEach { id -> repo.addSongs(id, songs) }
                        toRemove.forEach { id -> repo.removeSongs(id, songs) }
                        val parts = buildList {
                            if (toAdd.isNotEmpty()) {
                                add("added to ${toAdd.size} playlist${if (toAdd.size == 1) "" else "s"}")
                            }
                            if (toRemove.isNotEmpty()) {
                                add("removed from ${toRemove.size}")
                            }
                        }
                        val msg = parts.joinToString(", ").ifEmpty { "No changes" }
                            .replaceFirstChar { it.uppercase() }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Done")
            }
        }
    }
}
