package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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

    var editMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            repo.setCustomImage(playlistId, uri.toString())
            Toast.makeText(context, "Cover updated", Toast.LENGTH_SHORT).show()
        }
    }

    // Theme from first track / cover art — same pipeline as album pages
    LaunchedEffect(playlist?.id, playlist?.customImageUri, playlist?.songs?.firstOrNull()?.songKey) {
        val seed = playlist?.songs?.firstOrNull()
        themeColors = themeService.themeFromSong(context, seed, base).colors
    }

    LaunchedEffect(playlist?.name, editMode) {
        if (editMode) editName = playlist?.name.orEmpty()
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

    fun saveNameAndExitEdit() {
        val trimmed = editName.trim()
        if (trimmed.isNotEmpty() && trimmed != playlist?.name) {
            scope.launch {
                repo.rename(playlistId, trimmed)
                Toast.makeText(context, "Renamed", Toast.LENGTH_SHORT).show()
            }
        }
        editMode = false
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
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = themeColors.onContainer
                    )
                }
                Text(
                    if (editMode) "Editing" else "Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    color = themeColors.onContainer,
                    modifier = Modifier.weight(1f)
                )
                if (editMode) {
                    TextButton(onClick = { saveNameAndExitEdit() }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = themeColors.onContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Done", color = themeColors.onContainer)
                    }
                } else {
                    IconButton(onClick = {
                        editName = pl.name
                        editMode = true
                    }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = themeColors.onContainer
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = themeColors.onContainer
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Box {
                    PlaylistCoverArt(pl, size = 160.dp)
                    if (editMode) {
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

                if (editMode) {
                    BasicTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = themeColors.onContainer
                        ),
                        cursorBrush = SolidColor(themeColors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                themeColors.onContainer.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            Box {
                                if (editName.isEmpty()) {
                                    Text(
                                        "Playlist name",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = themeColors.onContainer.copy(alpha = 0.4f)
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
                        color = themeColors.onContainer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!pl.description.isNullOrBlank() && !editMode) {
                    Text(
                        pl.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeColors.onContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    formatTrackCount(pl.songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeColors.onContainer.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp)
                )
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
                        Icon(
                            Icons.Default.Radio,
                            contentDescription = "Start radio",
                            tint = themeColors.onContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (editMode) {
                Text(
                    "Tap name to rename · use arrows to reorder · swipe left to remove",
                    style = MaterialTheme.typography.labelMedium,
                    color = base.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(pl.songs, key = { i, s -> "$i-${s.songKey}" }) { index, song ->
                    if (editMode) {
                        EditPlaylistTrackRow(
                            song = song,
                            canMoveUp = index > 0,
                            canMoveDown = index < pl.songs.lastIndex,
                            onMoveUp = {
                                scope.launch { repo.move(pl.id, index, index - 1) }
                            },
                            onMoveDown = {
                                scope.launch { repo.move(pl.id, index, index + 1) }
                            },
                            onRemove = {
                                scope.launch {
                                    repo.removeAt(pl.id, index)
                                    Toast.makeText(context, "Removed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
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
                editMode = true
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

@Composable
private fun EditPlaylistTrackRow(
    song: Song,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val error = MaterialTheme.colorScheme.error
    val revealAlpha = ((-offsetX) / (threshold * 0.35f)).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxWidth()) {
        if (revealAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(error.copy(alpha = 0.2f * revealAlpha))
            ) {
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelLarge,
                    color = error.copy(alpha = revealAlpha),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(song.songKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold) onRemove()
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-threshold * 1.5f, 0f)
                        }
                    )
                }
                .padding(end = 8.dp)
        ) {
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
            AlbumArt(song = song, size = 40.dp, corner = 4.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                MarqueeText(
                    text = song.displayTitle,
                    style = MaterialTheme.typography.bodyLarge
                )
                MarqueeText(
                    text = song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }
}
