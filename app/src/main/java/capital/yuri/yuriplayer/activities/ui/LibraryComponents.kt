package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.data.label
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class LibrarySection { Songs, Albums, Artists, Untagged }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortDropdown(sortMode: SortMode, onSortModeChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val smallStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = "Sort: ${sortMode.label()}",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .height(40.dp),
            textStyle = smallStyle
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label(), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSortModeChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LibraryScreen(
    library: LibraryIndex,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    val allSongs by library.songs.collectAsState()
    val loading by library.isLoading.collectAsState()
    val lastScanned by library.lastScannedAt.collectAsState()
    val error by library.error.collectAsState()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var section by remember { mutableStateOf(LibrarySection.Songs) }

    val taggedSongs = remember(allSongs, sortMode, query) {
        library.search(query, sortMode, taggedOnly = true)
    }
    val untaggedSongs = remember(allSongs, sortMode, query) {
        library.search(query, sortMode, taggedOnly = false)
    }
    val albums = remember(allSongs, query) { library.albums(query, taggedOnly = true) }
    val artists = remember(allSongs, query) { library.artists(query, taggedOnly = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Filter songs, albums, artists…") }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibrarySection.entries.forEach { s ->
                val label = when (s) {
                    LibrarySection.Untagged -> "Untagged (${library.untaggedCount()})"
                    else -> s.name
                }
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = { Text(label) }
                )
            }
        }

        if (section == LibrarySection.Songs || section == LibrarySection.Untagged) {
            SortDropdown(sortMode) { sortMode = it }
        }

        val statusText = when {
            error != null -> error!!
            loading && allSongs.isEmpty() -> "Scanning library…"
            lastScanned > 0 -> {
                val whenStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(lastScanned))
                "${library.taggedCount()} tagged · ${library.untaggedCount()} untagged · updated $whenStr"
            }
            else -> "${allSongs.size} tracks"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        when (section) {
            LibrarySection.Songs -> SongList(taggedSongs, loading, onPlay) {
                onAddToQueue(it)
                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            }
            LibrarySection.Untagged -> SongList(untaggedSongs, loading, onPlay) {
                onAddToQueue(it)
                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            }
            LibrarySection.Albums -> {
                if (albums.isEmpty()) Text("No albums match.", modifier = Modifier.padding(16.dp))
                else LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(albums, key = { "${it.name}|${it.artist}" }) { album ->
                        AlbumRow(album) { onPlay(album.songs, 0) }
                    }
                }
            }
            LibrarySection.Artists -> {
                if (artists.isEmpty()) Text("No artists match.", modifier = Modifier.padding(16.dp))
                else LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                        ArtistRow(artist) { onPlay(artist.songs, 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    loading: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    if (songs.isEmpty() && !loading) {
        Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id to s.path }) { index, song ->
                SwipeAddSongRow(
                    song = song,
                    onClick = { onPlay(songs, index) },
                    onSwipeAdd = { onAddToQueue(song) }
                )
            }
        }
    }
}

@Composable
fun SwipeAddSongRow(
    song: Song,
    onClick: () -> Unit,
    onSwipeAdd: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Text(
            "+ Queue",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
        )
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(song) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX > threshold) onSwipeAdd()
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(0f, threshold * 1.5f)
                        }
                    )
                }
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song = song, size = 40.dp, corner = 4.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(
                    text = buildString {
                        song.trackNumber?.let { append("$it. ") }
                        append(song.displayTitle)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                MarqueeText(
                    text = "${song.displayArtist} • ${song.displayAlbum}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun AlbumRow(album: AlbumItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = album.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
            MarqueeText(
                text = "${album.displayArtist} · ${album.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ArtistRow(artist: ArtistItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        MarqueeText(
            text = artist.displayName,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "${artist.albumCount} albums · ${artist.trackCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun PlaceholderScreen(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
