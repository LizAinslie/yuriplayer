package capital.yuri.yuriplayer.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.activities.ui.AlbumArt
import capital.yuri.yuriplayer.activities.ui.QueuePanel
import capital.yuri.yuriplayer.activities.ui.theme.YuriPlayerTheme
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.data.label
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import capital.yuri.yuriplayer.player.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val libraryIndex: LibraryIndex by inject()
    private val playerController: PlayerController by inject()

    private var isCarMode = false

    /** Set when launched from the media notification. */
    private val openPlayerState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) libraryIndex.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isCarMode = intent?.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent?.getBooleanExtra("car_mode", false) == true
        openPlayerState.value = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        enableEdgeToEdge()
        setContent {
            YuriPlayerTheme {
                val openPlayer by openPlayerState
                YuriApp(
                    library = libraryIndex,
                    player = playerController,
                    isCarMode = isCarMode,
                    openPlayerInitially = openPlayer,
                    onPlayerOpened = { openPlayerState.value = false }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerController.bind()
    }

    override fun onDestroy() {
        playerController.unbind()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nowCar = intent.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent.getBooleanExtra("car_mode", false)
        if (nowCar && !isCarMode) isCarMode = true
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            openPlayerState.value = true
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "capital.yuri.yuriplayer.extra.OPEN_PLAYER"
    }
}

private enum class TopTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Library("Library", Icons.Default.LibraryMusic),
    MyStuff("My Stuff", Icons.Default.Favorite)
}

private enum class LibrarySection { Songs, Albums, Artists, Untagged }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YuriApp(
    library: LibraryIndex,
    player: PlayerController,
    isCarMode: Boolean,
    openPlayerInitially: Boolean = false,
    onPlayerOpened: () -> Unit = {}
) {
    var topTab by remember { mutableStateOf(TopTab.Library) }
    var playerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(openPlayerInitially) {
        if (openPlayerInitially) {
            playerExpanded = true
            onPlayerOpened()
        }
    }

    var currentSong by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var snapshot by remember { mutableStateOf(QueueSnapshot()) }

    val connected by player.isConnected.collectAsState()

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            currentSong = player.getCurrentSong()
            playing = player.isPlayingNow()
            positionMs = player.getPositionMs()
            durationMs = player.getDurationMs()
            snapshot = player.getQueueSnapshot()
            delay(400)
        }
    }

    BackHandler(enabled = playerExpanded) { playerExpanded = false }

    if (playerExpanded) {
        FullPlayerScreen(
            song = currentSong,
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            snapshot = snapshot,
            onCollapse = { playerExpanded = false },
            onToggle = { player.togglePlayPause() },
            onPrev = { player.skipToPrevious() },
            onNext = { player.skipToNext() },
            onSeek = { player.seekTo(it) },
            onToggleShuffle = { player.toggleShuffle() },
            onCycleRepeat = { player.cycleRepeatMode() },
            onPlayItem = { lane, index -> player.playQueueItem(lane, index) },
            onMoveHot = { f, t -> player.moveHot(f, t) },
            onMoveCold = { f, t -> player.moveCold(f, t) },
            onRemoveHot = { player.removeFromHot(it) },
            onRemoveCold = { player.removeFromCold(it) }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TopTab.entries.forEach { tab ->
                            val selected = topTab == tab
                            IconButton(onClick = { topTab = tab }) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (topTab == TopTab.Library) {
                        val loading by library.isLoading.collectAsState()
                        IconButton(onClick = { library.refresh() }) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh library")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            MiniPlayerBar(
                song = currentSong,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                onToggle = { player.togglePlayPause() },
                onExpand = { playerExpanded = true }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (topTab) {
                TopTab.Home -> PlaceholderScreen("Home", "Pin playlists and shortcuts here later.")
                TopTab.Library -> LibraryScreen(
                    library = library,
                    onPlay = { songs, index -> player.playSource(songs, index) },
                    onAddToHot = { player.addToHotQueue(it) }
                )
                TopTab.MyStuff -> PlaceholderScreen(
                    "My Stuff",
                    "Favorites, playlists, and saved albums/artists will live here."
                )
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortDropdown(sortMode: SortMode, onSortModeChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
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
            modifier = Modifier.menuAnchor().fillMaxWidth().height(48.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label()) },
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
fun MiniPlayerBar(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onExpand: () -> Unit
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            detectVerticalDragGestures { _, dragAmount ->
                if (dragAmount < -12f) onExpand()
            }
        }
    ) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArt(song = song, size = 44.dp, corner = 6.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song?.displayTitle ?: "Not playing",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song?.displayArtist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
            }
        }
    }
}

@Composable
fun FullPlayerScreen(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    snapshot: QueueSnapshot,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (Int, Int) -> Unit,
    onMoveCold: (Int, Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit
) {
    var showQueue by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, durationMs, sliding) {
        if (!sliding && durationMs > 0) {
            sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.ExpandMore, contentDescription = "Close")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
            }

            if (showQueue) {
                QueuePanel(
                    snapshot = snapshot,
                    onPlayItem = onPlayItem,
                    onMoveHot = onMoveHot,
                    onMoveCold = onMoveCold,
                    onRemoveHot = onRemoveHot,
                    onRemoveCold = onRemoveCold,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                AlbumArt(
                    song = song,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(1f),
                    corner = 12.dp
                )
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    song?.displayTitle ?: "Not playing",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    song?.displayArtist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    song?.displayAlbum ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliding = true; sliderPosition = it },
                    onValueChangeFinished = {
                        sliding = false
                        if (durationMs > 0) onSeek((sliderPosition * durationMs).toLong())
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle cold queue",
                            tint = if (snapshot.shuffleEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onPrev) {
                        Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play",
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = onCycleRepeat) {
                        val (icon, tintOn) = when (snapshot.repeatMode) {
                            RepeatMode.OFF -> Icons.Default.Repeat to false
                            RepeatMode.ONE -> Icons.Default.RepeatOne to true
                            RepeatMode.COLD -> Icons.Default.Repeat to true
                        }
                        Icon(
                            icon,
                            contentDescription = "Repeat: ${snapshot.repeatMode.name}",
                            tint = if (tintOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Text(
                    when (snapshot.repeatMode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ONE -> "Repeat one"
                        RepeatMode.COLD -> "Repeat cold queue"
                    } + if (snapshot.shuffleEnabled) " · Shuffle on" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Lyrics coming later",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
fun LibraryScreen(
    library: LibraryIndex,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToHot: (Song) -> Unit
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
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
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
            LibrarySection.Songs -> SongList(
                taggedSongs, loading, onPlay,
                onAddToHot = {
                    onAddToHot(it)
                    Toast.makeText(context, "Added to hot queue", Toast.LENGTH_SHORT).show()
                }
            )
            LibrarySection.Untagged -> SongList(
                untaggedSongs, loading, onPlay,
                onAddToHot = {
                    onAddToHot(it)
                    Toast.makeText(context, "Added to hot queue", Toast.LENGTH_SHORT).show()
                }
            )
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
    onAddToHot: (Song) -> Unit
) {
    if (songs.isEmpty() && !loading) {
        Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id to s.path }) { index, song ->
                SwipeAddSongRow(
                    song = song,
                    onClick = { onPlay(songs, index) },
                    onSwipeAdd = { onAddToHot(song) }
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
            "+ Hot queue",
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
                Text(
                    buildString {
                        song.trackNumber?.let { append("$it. ") }
                        append(song.displayTitle)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${song.displayArtist} • ${song.displayAlbum}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AlbumRow(album: AlbumItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(album.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.displayArtist} · ${album.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ArtistRow(artist: ArtistItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(artist.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${artist.albumCount} albums · ${artist.trackCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
