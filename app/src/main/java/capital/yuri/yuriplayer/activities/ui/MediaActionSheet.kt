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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.player.PlayerController
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun MediaSheetHeader(
    song: Song?,
    title: String,
    subtitle: String,
    artSize: androidx.compose.ui.unit.Dp = 56.dp
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
fun MediaSheetItem(
    label: String,
    enabled: Boolean = true,
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
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun MediaSheetBottomPad() {
    Spacer(modifier = Modifier.height(28.dp))
}

private fun songArtistNames(song: Song): List<String> {
    val credits = song.creditArtists
    if (credits.isNotEmpty()) return credits
    val single = song.effectiveAlbumArtist?.takeIf { it.isNotBlank() }
        ?: song.artist?.takeIf { it.isNotBlank() }
    return listOfNotNull(single)
}

/** Shared song sheet — keep every entry point in sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongContextSheet(
    song: Song,
    onDismiss: () -> Unit,
    /** Hide when already viewing this song's album. */
    hideGoToAlbum: Boolean = false,
    onGoToAlbum: (() -> Unit)? = null,
    /** Called with the chosen artist name (picker when multiple credits). */
    onGoToArtist: ((String) -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddToMyStuff: (() -> Unit)? = null
) {
    val pinStore: MyStuffPinStore = koinInject()
    val player: PlayerController = koinInject()
    val context = LocalContext.current
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showArtistPicker by remember { mutableStateOf(false) }
    val artists = remember(song) { songArtistNames(song) }

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
        ModalBottomSheet(
            onDismissRequest = {
                showArtistPicker = false
                onDismiss()
            },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                "Go to artist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Text(
                song.displayTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            artists.forEach { name ->
                MediaSheetItem(name) {
                    showArtistPicker = false
                    onDismiss()
                    onGoToArtist?.invoke(name)
                }
            }
            MediaSheetBottomPad()
        }
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
        MediaSheetItem("Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) onAddToMyStuff()
            else {
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.SONG,
                        id = song.songKey,
                        title = song.displayTitle,
                        subtitle = song.displayArtist
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        }
        if (!hideGoToAlbum && onGoToAlbum != null) {
            MediaSheetItem("Go to album") {
                onDismiss()
                onGoToAlbum()
            }
        }
        if (onGoToArtist != null && artists.isNotEmpty()) {
            MediaSheetItem("Go to artist") {
                if (artists.size == 1) {
                    onDismiss()
                    onGoToArtist(artists.first())
                } else {
                    showArtistPicker = true
                }
            }
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
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current
    var showPlaylistPicker by remember { mutableStateOf(false) }

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
        if (onStartRadio != null) {
            MediaSheetItem("Start radio") {
                onDismiss()
                onStartRadio()
            }
        }
        if (onAddToQueue != null) {
            MediaSheetItem("Add to queue") {
                onDismiss()
                onAddToQueue()
            }
        }
        MediaSheetItem("Add to playlist") {
            showPlaylistPicker = true
        }
        MediaSheetItem("Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) onAddToMyStuff()
            else {
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.ALBUM,
                        id = albumKey(album.name, album.artist),
                        title = album.displayName,
                        subtitle = album.displayArtist
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        }
        if (onGoToArtist != null) {
            MediaSheetItem("Go to artist") {
                onDismiss()
                onGoToArtist()
            }
        }
        if (onEditMetadata != null) {
            MediaSheetItem("Edit album metadata") {
                onDismiss()
                onEditMetadata()
            }
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
    onAddToMyStuff: (() -> Unit)? = null
) {
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        MediaSheetHeader(
            song = artist.songs.firstOrNull(),
            title = artist.displayName,
            subtitle = "Artist"
        )
        if (onStartRadio != null) {
            MediaSheetItem("Start radio") {
                onDismiss()
                onStartRadio()
            }
        }
        MediaSheetItem("Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) onAddToMyStuff()
            else {
                val key = artistKey(artist.name) ?: return@MediaSheetItem
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.ARTIST,
                        id = key,
                        title = artist.displayName,
                        subtitle = "Artist"
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        }
        if (onChangeImage != null) {
            MediaSheetItem("Change image") {
                onDismiss()
                onChangeImage()
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
    val pinStore: MyStuffPinStore = koinInject()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        MediaSheetHeader(
            song = playlist.songs.firstOrNull(),
            title = playlist.name,
            subtitle = "Playlist"
        )
        if (onStartRadio != null) {
            MediaSheetItem("Start radio") {
                onDismiss()
                onStartRadio()
            }
        }
        MediaSheetItem("Add to My Stuff") {
            onDismiss()
            if (onAddToMyStuff != null) onAddToMyStuff()
            else {
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.PLAYLIST,
                        id = playlist.id,
                        title = playlist.name,
                        subtitle = "Playlist"
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        }
        if (onChangeCover != null) {
            MediaSheetItem("Change cover") {
                onDismiss()
                onChangeCover()
            }
        }
        if (onEdit != null) {
            MediaSheetItem("Edit playlist") {
                onDismiss()
                onEdit()
            }
        }
        if (onDelete != null) {
            MediaSheetItem("Delete playlist") {
                onDismiss()
                onDelete()
            }
        }
        MediaSheetBottomPad()
    }
}

/**
 * Multi-select playlists + New playlist.
 * Playlists that already contain the song are pre-checked; unchecking removes.
 */
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

    LaunchedEffect(songs.map { it.songKey }) {
        val containing = repo.playlistsContaining(songs)
        initiallyIn = containing
        selected = containing
        ready = true
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

            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet — create one above.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(playlists, key = { it.id }) { pl ->
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
