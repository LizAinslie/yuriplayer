package capital.yuri.yuriplayer.activities.ui

import MarqueeText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private const val GRID_COLS = 2

/**
 * Reorder pins on the same 2-column grid as the Pins tab.
 * Long-press a card and drag to another slot; [MyStuffPinStore.movePin] on drop.
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

    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragHover by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // Approximate cell size for hover math; refined once grid measures.
    var cellW by remember { mutableFloatStateOf(0f) }
    var cellH by remember { mutableFloatStateOf(0f) }

    fun hoverAt(from: Int, ox: Float, oy: Float): Int {
        if (pins.isEmpty()) return from
        val w = cellW.takeIf { it > 1f } ?: return from
        val h = cellH.takeIf { it > 1f } ?: return from
        val col = from % GRID_COLS
        val row = from / GRID_COLS
        val newCol = (col + (ox / w).roundToInt()).coerceIn(0, GRID_COLS - 1)
        val maxRow = (pins.size - 1) / GRID_COLS
        val newRow = (row + (oy / h).roundToInt()).coerceIn(0, maxRow)
        return (newRow * GRID_COLS + newCol).coerceIn(0, pins.lastIndex)
    }

    fun endDrag() {
        val f = dragFrom
        val t = dragHover
        dragFrom = -1
        dragHover = -1
        dragOffsetX = 0f
        dragOffsetY = 0f
        if (f >= 0 && t >= 0 && f != t) pinStore.movePin(f, t)
    }

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
            "Long-press a pin and drag it to a new slot",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            val hGap = 12.dp
            val vGap = 12.dp
            val measuredW = with(density) {
                ((maxWidth - hGap) / GRID_COLS).toPx()
            }
            // Card is square art + ~text; approximate cell height from width ratio used in normal cards.
            val measuredH = measuredW * 1.35f
            cellW = measuredW + with(density) { hGap.toPx() }
            cellH = measuredH + with(density) { vGap.toPx() }

            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLS),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(hGap),
                verticalArrangement = Arrangement.spacedBy(vGap)
            ) {
                itemsIndexed(pins, key = { _, p -> p.key }) { index, pin ->
                    val isDragged = dragFrom == index
                    val isHover = !isDragged && dragFrom >= 0 && dragHover == index

                    ReorderGridCard(
                        pin = pin,
                        library = library,
                        playlists = playlists,
                        allSongs = allSongs,
                        isDragged = isDragged,
                        isHoverTarget = isHover,
                        dragOffsetX = if (isDragged) dragOffsetX else 0f,
                        dragOffsetY = if (isDragged) dragOffsetY else 0f,
                        modifier = Modifier
                            .zIndex(if (isDragged) 10f else 0f)
                            .pointerInput(index, pins.size, cellW, cellH) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragFrom = index
                                        dragHover = index
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = {
                                        dragFrom = -1
                                        dragHover = -1
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetX += amount.x
                                        dragOffsetY += amount.y
                                        dragHover = hoverAt(dragFrom, dragOffsetX, dragOffsetY)
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderGridCard(
    pin: StuffPin,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    isDragged: Boolean,
    isHoverTarget: Boolean,
    dragOffsetX: Float,
    dragOffsetY: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = when {
        isHoverTarget -> MaterialTheme.colorScheme.primary
        isDragged -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = dragOffsetX
                translationY = dragOffsetY
                shadowElevation = if (isDragged) 16f else 0f
                scaleX = if (isDragged) 1.04f else 1f
                scaleY = if (isDragged) 1.04f else 1f
                alpha = if (isDragged) 0.95f else 1f
            }
            .then(if (isDragged) Modifier.shadow(12.dp, shape) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isHoverTarget || isDragged) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .padding(12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            val artSize = maxWidth
            when (pin.kind) {
                StuffPinKind.ARTIST ->
                    ArtistArt(artistName = pin.title, size = artSize, circular = true)
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
