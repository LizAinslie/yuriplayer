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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistCover
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
import kotlin.math.sqrt

private val ExpandedHeaderBody = 360.dp
private val CollapsedBarHeight = 56.dp
private val GradientFadeLength = 200.dp
private val ReorderHeaderBody = 110.dp
private val EditHeaderBody = 380.dp

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

    val listState = rememberLazyListState()
    val autoScroll = rememberListDragAutoScroll(listState)

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) cropUri = uri
    }

    LaunchedEffect(playlist?.id, playlist?.customImageUri, playlist?.songs?.size) {
        val pl = playlist ?: return@LaunchedEffect
        val cover = PlaylistRepository.coverFor(pl)
        val uri: Uri? = when (cover.mode) {
            PlaylistCover.CoverMode.CUSTOM -> cover.customUri
            PlaylistCover.CoverMode.SINGLE,
            PlaylistCover.CoverMode.COLLAGE ->
                cover.artUris.firstOrNull() ?: pl.songs.firstOrNull()?.albumArtUri
            PlaylistCover.CoverMode.EMPTY -> null
        }
        themeColors = if (uri != null) {
            themeService.themeFromUri(
                context = context,
                key = "playlist:${pl.id}:${uri}",
                uri = uri,
                base = base
            ).colors
        } else {
            themeService.themeFromSong(context, pl.songs.firstOrNull(), base).colors
        }
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
    val fadePx = with(density) { GradientFadeLength.toPx() }

    val collapseRangePx = with(density) { (ExpandedHeaderBody - CollapsedBarHeight).toPx() }
    var collapsePx by remember { mutableFloatStateOf(0f) }
    val f = if (mode == PlaylistMode.Browse) {
        (collapsePx / collapseRangePx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val heightF = sqrt(f.toDouble()).toFloat()
    val headerBodyH = when (mode) {
        PlaylistMode.Browse -> ExpandedHeaderBody * (1f - heightF) + CollapsedBarHeight * heightF
        PlaylistMode.EditDetails -> EditHeaderBody
        PlaylistMode.Reorder -> ReorderHeaderBody
    }

    val nestedScroll = remember(collapseRangePx, mode) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (mode != PlaylistMode.Browse) return Offset.Zero
                val delta = available.y
                if (delta < 0f && collapsePx < collapseRangePx) {
                    val consumed = (-delta).coerceAtMost(collapseRangePx - collapsePx)
                    collapsePx += consumed
                    return Offset(0f, -consumed)
                }
                if (delta > 0f && collapsePx > 0f &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    val consumed = delta.coerceAtMost(collapsePx)
                    collapsePx -= consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(playlistId) { collapsePx = 0f }
    LaunchedEffect(mode) { if (mode != PlaylistMode.Browse) collapsePx = 0f }

    val rootPlaylistNav = LocalPlaylistNav.current
    val nestedPlaylistNav = rootPlaylistNav.copy(
        startRadio = { startRadio() },
        changeCover = { pickCover.launch("image/*") },
        edit = {
            editName = pl.name
            editDescription = pl.description.orEmpty()
            mode = PlaylistMode.EditDetails
        },
        delete = {
            scope.launch {
                repo.delete(pl.id)
                onBack()
            }
        },
        addToMyStuff = {
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

    CompositionLocalProvider(LocalPlaylistNav provides nestedPlaylistNav) {
        ThemedStatusBar(color = artBg, enabled = true)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (mode == PlaylistMode.Browse) Modifier.nestedScroll(nestedScroll)
                    else Modifier
                )
                .background(defaultBg)
                .drawBehind {
                    val headerEnd = headerBodyH.toPx()
                    val solidEnd = headerEnd + with(density) { 48.dp.toPx() }
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
                                    artBg.copy(alpha = 0.85f),
                                    artBg.copy(alpha = 0.45f),
                                    artBg.copy(alpha = 0.15f),
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
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerBodyH)
                            .clipToBounds()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = (1f - heightF * 1.6f).coerceIn(0f, 1f)
                                }
                        ) {
                            when (mode) {
                                PlaylistMode.Browse -> PlaylistExpandedHero(
                                    playlist = pl,
                                    onArt = onArt,
                                    themeColors = themeColors,
                                    showPause = showPause,
                                    onPrimary = onPrimary,
                                    onStartRadio = { startRadio() },
                                    onEdit = {
                                        editName = pl.name
                                        editDescription = pl.description.orEmpty()
                                        mode = PlaylistMode.EditDetails
                                    },
                                    onReorder = { mode = PlaylistMode.Reorder },
                                    onMore = { showMenu = true }
                                )
                                PlaylistMode.EditDetails -> PlaylistEditHero(
                                    playlist = pl,
                                    editName = editName,
                                    onEditNameChange = { editName = it },
                                    editDescription = editDescription,
                                    onEditDescriptionChange = { editDescription = it },
                                    onArt = onArt,
                                    themeColors = themeColors,
                                    onPickCover = { pickCover.launch("image/*") },
                                    onDone = { saveDetailsAndExit() },
                                    onBackToBrowse = { mode = PlaylistMode.Browse }
                                )
                                PlaylistMode.Reorder -> PlaylistReorderHero(
                                    onArt = onArt,
                                    onDone = { mode = PlaylistMode.Browse },
                                    onBackToBrowse = { mode = PlaylistMode.Browse }
                                )
                            }
                        }

                        if (mode == PlaylistMode.Browse && heightF > 0.2f) {
                            CollapsedPlaylistBar(
                                playlist = pl,
                                showPause = showPause,
                                onArt = onArt,
                                themeColors = themeColors,
                                onBack = onBack,
                                onPrimary = onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer {
                                        alpha = ((heightF - 0.2f) / 0.35f).coerceIn(0f, 1f)
                                    }
                            )
                        }

                        if (mode == PlaylistMode.Browse && heightF < 0.5f) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .graphicsLayer {
                                        alpha = (1f - heightF * 2.2f).coerceIn(0f, 1f)
                                    }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = onArt
                                )
                            }
                        }
                    }
                }

                val rowH = with(density) { 64.dp.toPx() }
                var dragFrom by remember { mutableIntStateOf(-1) }
                var dragHover by remember { mutableIntStateOf(-1) }
                var dragOffset by remember { mutableFloatStateOf(0f) }

                autoScroll.onScrolled = { delta ->
                    if (dragFrom >= 0) {
                        dragOffset += delta
                        val raw = dragFrom + (dragOffset / rowH).roundToInt()
                        dragHover = raw.coerceIn(0, (pl.songs.size - 1).coerceAtLeast(0))
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = if (mode == PlaylistMode.Browse) 8.dp else 0.dp,
                        bottom = 96.dp
                    )
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
                                                    // layout index == song index (no headers)
                                                    autoScroll.onDragStart(
                                                        fingerYFromListItem(listState, index, 0f)
                                                    )
                                                },
                                                onDragEnd = {
                                                    autoScroll.onDragEnd()
                                                    val from = dragFrom
                                                    val to = dragHover
                                                    dragFrom = -1
                                                    dragHover = -1
                                                    dragOffset = 0f
                                                    if (from >= 0 && to >= 0 && from != to) {
                                                        scope.launch { repo.move(pl.id, from, to) }
                                                    }
                                                },
                                                onDragCancel = {
                                                    autoScroll.onDragEnd()
                                                    dragFrom = -1
                                                    dragHover = -1
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    autoScroll.onDragDelta(amount.y)
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
                onDismiss = { showMenu = false }
            )
        }
    }
}

@Composable
private fun PlaylistExpandedHero(
    playlist: Playlist,
    onArt: Color,
    themeColors: PlayerColors,
    showPause: Boolean,
    onPrimary: () -> Unit,
    onStartRadio: () -> Unit,
    onEdit: () -> Unit,
    onReorder: () -> Unit,
    onMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 36.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlaylistCoverArt(playlist, size = 160.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            MarqueeText(
                text = playlist.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = onArt,
                modifier = Modifier.fillMaxWidth()
            )
            if (!playlist.description.isNullOrBlank()) {
                Text(
                    playlist.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onArt.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                formatTrackCount(playlist.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = onArt.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onStartRadio) {
                    Icon(Icons.Default.Radio, contentDescription = "Start radio", tint = onArt)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit details", tint = onArt)
                }
                IconButton(onClick = onReorder) {
                    Icon(Icons.Default.Reorder, contentDescription = "Reorder tracks", tint = onArt)
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = onArt)
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditHero(
    playlist: Playlist,
    editName: String,
    onEditNameChange: (String) -> Unit,
    editDescription: String,
    onEditDescriptionChange: (String) -> Unit,
    onArt: Color,
    themeColors: PlayerColors,
    onPickCover: () -> Unit,
    onDone: () -> Unit,
    onBackToBrowse: () -> Unit
) {
    val base = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBackToBrowse) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onArt)
            }
            Text(
                "Edit details",
                style = MaterialTheme.typography.titleMedium,
                color = onArt,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDone) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = onArt)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Done", color = onArt)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Box {
                PlaylistCoverArt(playlist, size = 160.dp)
                IconButton(
                    onClick = onPickCover,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(base.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Change cover")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            BasicTextField(
                value = editName,
                onValueChange = onEditNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = onArt),
                cursorBrush = SolidColor(themeColors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(onArt.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (editName.isEmpty()) {
                            Text("Playlist name", style = MaterialTheme.typography.headlineSmall, color = onArt.copy(alpha = 0.4f))
                        }
                        inner()
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = editDescription,
                onValueChange = onEditDescriptionChange,
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
                            Text("Description (optional)", style = MaterialTheme.typography.bodyMedium, color = onArt.copy(alpha = 0.4f))
                        }
                        inner()
                    }
                }
            )
            Text(
                formatTrackCount(playlist.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = onArt.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PlaylistReorderHero(
    onArt: Color,
    onDone: () -> Unit,
    onBackToBrowse: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBackToBrowse) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onArt)
            }
            Text("Reorder", style = MaterialTheme.typography.titleMedium, color = onArt, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done", color = onArt) }
        }
        Text(
            "Long-press and drag to reorder · hold near top/bottom to scroll · swipe left to remove",
            style = MaterialTheme.typography.labelMedium,
            color = onArt.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun CollapsedPlaylistBar(
    playlist: Playlist,
    showPause: Boolean,
    onArt: Color,
    themeColors: PlayerColors,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CollapsedBarHeight)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onArt)
        }
        PlaylistCoverArt(playlist, size = 40.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = onArt
            )
            Text(
                formatTrackCount(playlist.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = onArt.copy(alpha = 0.65f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onPrimary,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(themeColors.accent)
        ) {
            Icon(
                if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (showPause) "Pause" else "Play",
                tint = themeColors.onAccent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
