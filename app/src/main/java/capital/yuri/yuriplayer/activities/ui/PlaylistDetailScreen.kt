package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val PlaylistHeaderHeight = 280.dp
private val PlaylistGradientFade = 180.dp

private enum class PlaylistMode { Browse, EditDetails, Reorder }

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    nowPlaying: Song? = null,
    isSourceActive: Boolean = false,
    isPlaying: Boolean = false,
    isPlaybackActive: Boolean = false,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onAddToQueue: (Song) -> Unit,
    onStartRadio: (() -> Unit)? = null
) {
    val repo: PlaylistRepository = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    val player: PlayerController = koinInject()
    val themeService: ThemeService = koinInject()
    val playlist by repo.observePlaylist(playlistId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val base = MaterialTheme.colorScheme
    val density = LocalDensity.current

    var mode by remember { mutableStateOf(PlaylistMode.Browse) }
    var showMenu by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) cropUri = uri
    }

    LaunchedEffect(playlist?.id, playlist?.customImageUri, playlist?.songs?.firstOrNull()?.songKey) {
        val seed = playlist?.songs?.firstOrNull()
        themeColors = themeService.themeFromSong(context, seed, base).colors
    }

    LaunchedEffect(playlist?.name, playlist?.description, mode) {
        if (mode == PlaylistMode.EditDetails) {
            editName = playlist?.name.orEmpty()
            editDescription = playlist?.description.orEmpty()
        }
    }

    fun startRadio() {
        val songs = playlist?.songs.orEmpty()
        if (songs.isEmpty()) {
            Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (onStartRadio != null) onStartRadio()
        else player.startPlaylistRadio(songs, playlist?.name)
        Toast.makeText(context, "Radio · ${playlist?.name ?: "Playlist"}", Toast.LENGTH_SHORT).show()
    }

    fun saveDetailsAndExit() {
        val trimmed = editName.trim()
        if (trimmed.isNotEmpty()) {
            scope.launch {
                repo.rename(playlistId, trimmed, editDescription.trim().ifEmpty { null })
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        mode = PlaylistMode.Browse
    }

    if (cropUri != null) {
        ImageCropScreen(
            sourceUri = cropUri!!,
            title = "Crop cover",
            aspect = 1f,
            onCancel = { cropUri = null },
            onCropped = { cropped ->
                cropUri = null
                scope.launch {
                    repo.setCustomImage(playlistId, cropped.toString())
                    Toast.makeText(context, "Cover updated", Toast.LENGTH_SHORT).show()
                }
            }
        )
        return
    }

    val pl = playlist
    if (pl == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Playlist not found", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val showPause = isSourceActive && isPlaying
    val onPrimary = {
        if (isSourceActive) onTogglePlayPause()
        else if (pl.songs.isNotEmpty()) onPlay(pl.songs, 0)
    }

    val artBg = themeColors.container
    val onArt = themeColors.onContainer
    val defaultBg = base.background
    val fadePx = with(density) { PlaylistGradientFade.toPx() }
    val headerPx = with(density) { PlaylistHeaderHeight.toPx() }

    ThemedStatusBar(color = artBg, enabled = true)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(defaultBg)
            .drawBehind {
                val solidEnd = headerPx
                val fadeEnd = solidEnd + fadePx
                drawRect(
                    color = artBg,
                    topLeft = Offset.Zero,
                    size = Size(size.width, solidEnd.coerceAtMost(size.height))
                )
                if (fadeEnd > solidEnd) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                artBg,
                                artBg.copy(alpha = 0.75f),
                                artBg.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            startY = solidEnd,
                            endY = fadeEnd
                        ),
                        topLeft = Offset(0f, solidEnd),
                        size = Size(
                            size.width,
                            (fadeEnd - solidEnd).coerceAtMost(size.height - solidEnd)
                        )
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = {
                    when (mode) {
                        PlaylistMode.Browse -> onBack()
                        else -> mode = PlaylistMode.Browse
                    }
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onArt
                    )
                }
                Text(
                    when (mode) {
                        PlaylistMode.EditDetails -> "Edit details"
                        PlaylistMode.Reorder -> "Reorder"
                        PlaylistMode.Browse -> "Playlist"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = onArt,
                    modifier = Modifier.weight(1f)
                )
                when (mode) {
                    PlaylistMode.EditDetails -> {
                        TextButton(onClick = { saveDetailsAndExit() }) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = onArt
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Done", color = onArt)
                        }
                    }
                    PlaylistMode.Reorder -> {
                        TextButton(onClick = { mode = PlaylistMode.Browse }) {
                            Text("Done", color = onArt)
                        }
                    }
                    PlaylistMode.Browse -> {
                        IconButton(onClick = {
                            editName = pl.name
                            editDescription = pl.description.orEmpty()
                            mode = PlaylistMode.EditDetails
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit details", tint = onArt)
                        }
                        IconButton(onClick = { mode = PlaylistMode.Reorder }) {
                            Icon(Icons.Default.Reorder, contentDescription = "Reorder tracks", tint = onArt)
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = onArt)
                        }
                    }
                }
            }

            if (mode != PlaylistMode.Reorder) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Box {
                        PlaylistCoverArt(pl, size = 160.dp)
                        if (mode == PlaylistMode.EditDetails) {
                            IconButton(
                                onClick = { pickCover.launch("image/*") },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .background(
                                        base.surface.copy(alpha = 0.9f),
                                        RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Icon(Icons.Default.Image, contentDescription = "Change cover")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (mode == PlaylistMode.EditDetails) {
                        BasicTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = onArt
                            ),
                            cursorBrush = SolidColor(themeColors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(onArt.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (editName.isEmpty()) {
                                        Text(
                                            "Playlist name",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = onArt.copy(alpha = 0.4f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BasicTextField(
                            value = editDescription,
                            onValueChange = { editDescription = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = onArt),
                            cursorBrush = SolidColor(themeColors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .background(onArt.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (editDescription.isEmpty()) {
                                        Text(
                                            "Description (optional)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onArt.copy(alpha = 0.4f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                    } else {
                        MarqueeText(
                            text = pl.name,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = onArt,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!pl.description.isNullOrBlank()) {
                            Text(
                                pl.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onArt.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    Text(
                        formatTrackCount(pl.songs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = onArt.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (mode == PlaylistMode.Browse) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onPrimary,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(themeColors.accent)
                            ) {
                                Icon(
                                    if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (showPause) "Pause" else "Play",
                                    tint = themeColors.onAccent,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { startRadio() }) {
                                Icon(Icons.Default.Radio, contentDescription = "Start radio", tint = onArt)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    "Long-press and drag to reorder · swipe left to remove",
                    style = MaterialTheme.typography.labelMedium,
                    color = onArt.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            val rowH = with(density) { 64.dp.toPx() }
            var dragFrom by remember { mutableIntStateOf(-1) }
            var dragHover by remember { mutableIntStateOf(-1) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(pl.songs, key = { i, s -> "$i-${s.songKey}" }) { index, song ->
                    if (mode == PlaylistMode.Reorder) {
                        val isDragged = dragFrom == index
                        val shift = when {
                            !isDragged && dragFrom >= 0 && dragFrom < dragHover &&
                                index in (dragFrom + 1)..dragHover -> -rowH
                            !isDragged && dragFrom >= 0 && dragFrom > dragHover &&
                                index in dragHover until dragFrom -> rowH
                            isDragged -> dragOffset
                            else -> 0f
                        }
                        var swipeX by remember { mutableFloatStateOf(0f) }
                        val threshold = with(density) { 96.dp.toPx() }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 5f else 0f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .graphicsLayer {
                                        translationY = shift
                                        translationX = swipeX
                                    }
                                    .fillMaxWidth()
                                    .background(Color.Transparent)
                                    .pointerInput(index, pl.songs.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                swipeX = 0f
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
                                                if (f >= 0 && t >= 0 && f != t) {
                                                    scope.launch { repo.move(pl.id, f, t) }
                                                }
                                            },
                                            onDragCancel = {
                                                dragFrom = -1
                                                dragHover = -1
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val raw = dragFrom +
                                                    (dragOffset / rowH).roundToInt()
                                                dragHover = raw.coerceIn(
                                                    0,
                                                    (pl.songs.size - 1).coerceAtLeast(0)
                                                )
                                            }
                                        )
                                    }
                                    .pointerInput(index) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (swipeX < -threshold) {
                                                    scope.launch {
                                                        repo.removeAt(pl.id, index)
                                                        Toast.makeText(
                                                            context,
                                                            "Removed",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                                swipeX = 0f
                                            },
                                            onDragCancel = { swipeX = 0f },
                                            onHorizontalDrag = { _, d ->
                                                swipeX = (swipeX + d).coerceIn(
                                                    -threshold * 1.5f,
                                                    0f
                                                )
                                            }
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Drag",
                                    tint = onArt.copy(alpha = 0.45f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                AlbumArt(song = song, size = 44.dp, corner = 4.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    MarqueeText(
                                        text = song.displayTitle,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = onArt
                                    )
                                    MarqueeText(
                                        text = song.displayArtist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = onArt.copy(alpha = 0.6f)
                                    )
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        repo.removeAt(pl.id, index)
                                        Toast.makeText(context, "Removed", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    } else if (mode == PlaylistMode.Browse) {
                        SwipeAddSongRow(
                            song = song,
                            onClick = { onPlay(pl.songs, index) },
                            onSwipeAdd = {
                                onAddToQueue(song)
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                            },
                            isPlaying = song.isSameAs(nowPlaying),
                            isPlaybackActive = isPlaybackActive || isPlaying,
                            transparentSurface = true
                        )
                    }
                }
            }
        }
    }

    if (showMenu) {
        PlaylistContextSheet(
            playlist = pl,
            onDismiss = { showMenu = false },
            onStartRadio = { startRadio() },
            onChangeCover = { pickCover.launch("image/*") },
            onEdit = {
                editName = pl.name
                editDescription = pl.description.orEmpty()
                mode = PlaylistMode.EditDetails
            },
            onDelete = {
                scope.launch {
                    repo.delete(pl.id)
                    onBack()
                }
            },
            onAddToMyStuff = {
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.PLAYLIST,
                        id = pl.id,
                        title = pl.name,
                        subtitle = "Playlist"
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
