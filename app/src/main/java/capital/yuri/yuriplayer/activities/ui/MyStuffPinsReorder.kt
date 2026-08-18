package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Full-screen pin reorder: long-press drag rows, Done exits.
 * Uses [MyStuffPinStore.movePin].
 */
@Composable
fun MyStuffPinsReorderMode(
    pins: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onDone: () -> Unit
) {
    val pinStore: MyStuffPinStore = koinInject()
    val density = LocalDensity.current
    val rowH = with(density) { 64.dp.toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragHover by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Reorder pins",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            TextButton(onClick = onDone) {
                Text("Done")
            }
        }
        Text(
            "Long-press and drag to rearrange",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(pins, key = { _, p -> p.key }) { index, pin ->
                val isDragged = dragFrom == index
                val shift = when {
                    !isDragged && dragFrom >= 0 && dragFrom < dragHover &&
                        index in (dragFrom + 1)..dragHover -> -rowH
                    !isDragged && dragFrom >= 0 && dragFrom > dragHover &&
                        index in dragHover until dragFrom -> rowH
                    isDragged -> dragOffset
                    else -> 0f
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 5f else 0f)
                        .graphicsLayer { translationY = shift }
                        .pointerInput(index, pins.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragFrom = index
                                    dragHover = index
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    val f = dragFrom
                                    val t = dragHover
                                    dragFrom = -1
                                    dragHover = -1
                                    dragOffset = 0f
                                    if (f >= 0 && t >= 0 && f != t) pinStore.movePin(f, t)
                                },
                                onDragCancel = {
                                    dragFrom = -1
                                    dragHover = -1
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val raw = dragFrom + (dragOffset / rowH).roundToInt()
                                    dragHover = raw.coerceIn(0, (pins.size - 1).coerceAtLeast(0))
                                }
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        // Inline small art via shared helpers living in MyStuffScreen scope
                        // — use AlbumArt/ArtistArt through a compact leading
                        PinReorderLeading(
                            pin = pin,
                            library = library,
                            playlists = playlists,
                            allSongs = allSongs
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        MarqueeText(
                            text = pin.title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (pin.subtitle.isNotBlank()) {
                            Text(
                                pin.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinReorderLeading(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>
) {
    when (pin.kind) {
        capital.yuri.yuriplayer.data.StuffPinKind.ARTIST ->
            ArtistArt(artistName = pin.title, size = 48.dp, circular = true)
        capital.yuri.yuriplayer.data.StuffPinKind.PLAYLIST -> {
            val pl = playlists.firstOrNull { it.id == pin.id }
            if (pl != null) PlaylistCoverArt(pl, size = 48.dp)
            else Icon(
                Icons.Default.DragHandle,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        capital.yuri.yuriplayer.data.StuffPinKind.ALBUM -> {
            val album = library.albums(taggedOnly = false)
                .firstOrNull {
                    capital.yuri.yuriplayer.data.albumKey(it.name, it.artist) == pin.id
                }
            AlbumArt(song = album?.songs?.firstOrNull(), size = 48.dp, corner = 4.dp)
        }
        capital.yuri.yuriplayer.data.StuffPinKind.SONG -> {
            val song = allSongs.firstOrNull { it.songKey == pin.id }
            AlbumArt(song = song, size = 48.dp, corner = 4.dp)
        }
    }
}
