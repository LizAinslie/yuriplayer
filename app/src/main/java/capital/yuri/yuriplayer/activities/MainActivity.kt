package capital.yuri.yuriplayer.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.activities.ui.theme.YuriPlayerTheme
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.data.label
import capital.yuri.yuriplayer.player.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val libraryIndex: LibraryIndex by inject()
    private val playerController: PlayerController by inject()

    private var isCarMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) libraryIndex.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isCarMode = intent?.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent?.getBooleanExtra("car_mode", false) == true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        enableEdgeToEdge()
        setContent {
            YuriPlayerTheme {
                YuriApp(
                    library = libraryIndex,
                    player = playerController,
                    isCarMode = isCarMode
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nowCar = intent.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent.getBooleanExtra("car_mode", false)
        if (nowCar && !isCarMode) isCarMode = true
    }
}

private enum class TopTab(
    val label: String,
    val icon: ImageVector
) {
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
    isCarMode: Boolean
) {
    var topTab by remember { mutableStateOf(TopTab.Library) }
    var playerExpanded by remember { mutableStateOf(false) }

    var currentSong by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var queue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(-1) }

    val connected by player.isConnected.collectAsState()

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            currentSong = player.getCurrentSong()
            playing = player.isPlayingNow()
            positionMs = player.getPositionMs()
            durationMs = player.getDurationMs()
            queue = player.getQueue()
            currentIndex = player.getCurrentIndex()
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
            queue = queue,
            currentIndex = currentIndex,
            onCollapse = { playerExpanded = false },
            onToggle = { player.togglePlayPause() },
            onPrev = { player.skipToPrevious() },
            onNext = { player.skipToNext() },
            onSeek = { player.seekTo(it) },
            onQueueItem = { index ->
                if (queue.isNotEmpty()) {
                    player.setPlaylist(queue, index)
                    player.play()
                }
            }
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
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    }
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
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
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
                TopTab.Home -> PlaceholderScreen(
                    title = "Home",
                    body = "Pin playlists and shortcuts here later."
                )
                TopTab.Library -> LibraryScreen(
                    library = library,
                    onPlay = { songs, index ->
                        player.setPlaylist(songs, index)
                        player.play()
                    }
                )
                TopTab.MyStuff -> PlaceholderScreen(
                    title = "My Stuff",
                    body = "Favorites, playlists, and saved albums/artists will live here."
                )
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
fun SortDropdown(
    sortMode: SortMode,
    onSortModeChange: (SortMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
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
                .height(48.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
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
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -12f) onExpand()
                }
            }
    ) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song?.displayTitle ?: "Not playing",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song?.displayArtist ?: "",
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
    queue: List<Song>,
    currentIndex: Int,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onQueueItem: (Int) -> Unit
) {
    var showQueue by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, durationMs, sliding) {
        if (!sliding && durationMs > 0) {
            sliderPosition = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
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
                Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(queue, key = { i, s -> s.id to i }) { index, item ->
                        val isCurrent = index == currentIndex
                        Text(
                            text = buildString {
                                if (isCurrent) append("▶ ")
                                append(item.displayTitle)
                                append(" — ")
                                append(item.displayArtist)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onQueueItem(index) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote, null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                    )
                }
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
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Lyrics coming later (tags / local files)",
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
    onPlay: (List<Song>, Int) -> Unit
) {
    val allSongs by library.songs.collectAsState()
    val loading by library.isLoading.collectAsState()
    val lastScanned by library.lastScannedAt.collectAsState()
    val error by library.error.collectAsState()

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
            SortDropdown(sortMode = sortMode, onSortModeChange = { sortMode = it })
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
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        when (section) {
            LibrarySection.Songs -> SongList(taggedSongs, loading, onPlay)
            LibrarySection.Untagged -> SongList(untaggedSongs, loading, onPlay)
            LibrarySection.Albums -> {
                if (albums.isEmpty()) {
                    Text("No albums match.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums, key = { normalizeAlbumKey(it) }) { album ->
                            AlbumRow(album) { onPlay(album.songs, 0) }
                        }
                    }
                }
            }
            LibrarySection.Artists -> {
                if (artists.isEmpty()) {
                    Text("No artists match.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                            ArtistRow(artist) { onPlay(artist.songs, 0) }
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeAlbumKey(album: AlbumItem): String {
    return listOf(album.name, album.artist)
        .joinToString("|") { it?.trim()?.lowercase().orEmpty() }
}

@Composable
private fun SongList(
    songs: List<Song>,
    loading: Boolean,
    onPlay: (List<Song>, Int) -> Unit
) {
    if (songs.isEmpty() && !loading) {
        Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id to s.path }) { index, song ->
                SongRow(song) { onPlay(songs, index) }
            }
        }
    }
}

@Composable
fun SongRow(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = buildString {
                song.trackNumber?.let { append("$it. ") }
                append(song.displayTitle)
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${song.displayArtist} • ${song.displayAlbum}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AlbumRow(album: AlbumItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(album.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${album.displayArtist} · ${album.trackCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
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
