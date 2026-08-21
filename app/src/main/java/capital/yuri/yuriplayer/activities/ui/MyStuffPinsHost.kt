package capital.yuri.yuriplayer.activities.ui

import MarqueeText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey

private sealed class HostPinCell {
    data class Filled(val pin: StuffPin) : HostPinCell()
    data object Empty : HostPinCell()
}

/**
 * Pins tab host: 2×3 surface cards, long-press sheet (Reorder + Remove),
 * reorder mode with drag list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyStuffPinsHost(
    pins: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onOpenPin: (StuffPin) -> Unit,
    onUnpin: (StuffPin) -> Unit,
    onAddPinSlot: () -> Unit,
    onPlayAll: () -> Unit
) {
    var reorderMode by remember { mutableStateOf(false) }
    var pinForSheet by remember { mutableStateOf<StuffPin?>(null) }

    if (reorderMode) {
        MyStuffPinsReorderMode(
            pins = pins,
            library = library,
            playlists = playlists,
            allSongs = allSongs,
            onDone = { reorderMode = false }
        )
        return
    }

    val emptyCount = (MyStuffPinStore.PIN_SLOTS - pins.size).coerceAtLeast(0)
    val cells = remember(pins, emptyCount) {
        pins.map { HostPinCell.Filled(it) } + List(emptyCount) { HostPinCell.Empty }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            cells.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { cell ->
                        Box(Modifier.weight(1f)) {
                            when (cell) {
                                is HostPinCell.Filled -> HostPinCard(
                                    pin = cell.pin,
                                    library = library,
                                    playlists = playlists,
                                    allSongs = allSongs,
                                    onClick = { onOpenPin(cell.pin) },
                                    onLongClick = { pinForSheet = cell.pin }
                                )
                                is HostPinCell.Empty -> HostEmptyPin(onClick = onAddPinSlot)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    pinForSheet?.let { pin ->
        PinActionSheetWithReorder(
            pin = pin,
            onDismiss = { pinForSheet = null },
            onReorder = {
                pinForSheet = null
                reorderMode = true
            },
            onUnpin = {
                onUnpin(pin)
                pinForSheet = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostPinCard(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp)
    ) {
        // Art fills the square slot with no extra surfaceVariant frame around it.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            val artSize = maxWidth
            when (pin.kind) {
                StuffPinKind.ARTIST ->
                    ArtistArt(
                        artistName = pin.title,
                        size = artSize,
                        circular = true
                    )
                StuffPinKind.PLAYLIST -> {
                    val pl = playlists.firstOrNull { it.id == pin.id }
                    if (pl != null) PlaylistCoverArt(pl, size = artSize)
                }
                StuffPinKind.ALBUM -> {
                    val album = library.albums(taggedOnly = false)
                        .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                    AlbumArt(
                        song = album?.songs?.firstOrNull(),
                        size = null,
                        corner = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                StuffPinKind.SONG -> {
                    val song = allSongs.firstOrNull { it.songKey == pin.id }
                    AlbumArt(
                        song = song,
                        size = null,
                        corner = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        MarqueeText(
            text = pin.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth()
        )
        if (pin.subtitle.isNotBlank()) {
            Text(
                text = pin.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HostEmptyPin(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add pin",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Add pin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}
