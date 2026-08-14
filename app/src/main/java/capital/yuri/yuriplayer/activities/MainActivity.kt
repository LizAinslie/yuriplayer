package capital.yuri.yuriplayer.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.activities.ui.AlbumDetailScreen
import capital.yuri.yuriplayer.activities.ui.ApplyStatusBarStack
import capital.yuri.yuriplayer.activities.ui.ArtistDetailScreen
import capital.yuri.yuriplayer.activities.ui.LibraryScreen
import capital.yuri.yuriplayer.activities.ui.LocalStatusBarStack
import capital.yuri.yuriplayer.activities.ui.MiniPlayerBar
import capital.yuri.yuriplayer.activities.ui.NowPlayingScreen
import capital.yuri.yuriplayer.activities.ui.PlaceholderScreen
import capital.yuri.yuriplayer.activities.ui.SettingsScreen
import capital.yuri.yuriplayer.activities.ui.StatusBarColorStack
import capital.yuri.yuriplayer.activities.ui.theme.YuriPlayerTheme
import capital.yuri.yuriplayer.data.ActivityTitleFormat
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.ColdSourceType
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueSnapshot
import capital.yuri.yuriplayer.player.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    private val libraryIndex: LibraryIndex by inject()
    private val playerController: PlayerController by inject()

    private var isCarMode = false
    private val openPlayerState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) libraryIndex.refresh()
    }

    private fun audioReadPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun ensureAudioPermission() {
        val perm = audioReadPermission()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            libraryIndex.refresh()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        isCarMode = intent?.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent?.getBooleanExtra("car_mode", false) == true
        openPlayerState.value = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        ensureAudioPermission()

        title = "YuriPlayer"
        enableEdgeToEdge()
        setContent {
            YuriPlayerTheme {
                val openPlayer by openPlayerState
                YuriApp(
                    library = libraryIndex,
                    player = playerController,
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

private sealed class DetailRoute {
    data class Album(val album: AlbumItem) : DetailRoute()
    data class Artist(val artist: ArtistItem) : DetailRoute()
    data object Settings : DetailRoute()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YuriApp(
    library: LibraryIndex,
    player: PlayerController,
    openPlayerInitially: Boolean = false,
    onPlayerOpened: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val themeStore: PlayerThemeStore = koinInject()
    val baseScheme = MaterialTheme.colorScheme

    val statusBarStack = remember(baseScheme.background) {
        StatusBarColorStack(baseScheme.background)
    }
    LaunchedEffect(baseScheme.background) {
        statusBarStack.replaceBase(baseScheme.background)
    }

    var topTab by remember { mutableStateOf(TopTab.Library) }
    var playerExpanded by remember { mutableStateOf(false) }
    var detailStack by remember { mutableStateOf<List<DetailRoute>>(emptyList()) }

    fun pushDetail(route: DetailRoute) {
        detailStack = detailStack + route
    }

    fun popDetail() {
        if (detailStack.isNotEmpty()) detailStack = detailStack.dropLast(1)
    }

    val detail = detailStack.lastOrNull()
    val edgeToEdgeDetail = detail is DetailRoute.Album || detail is DetailRoute.Artist

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
    var peekNext by remember { mutableStateOf<Song?>(null) }
    var peekPrev by remember { mutableStateOf<Song?>(null) }

    val connected by player.isConnected.collectAsState()

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            currentSong = player.getCurrentSong()
            playing = player.isPlayingNow()
            positionMs = player.getPositionMs()
            durationMs = player.getDurationMs()
            snapshot = player.getQueueSnapshot()
            peekNext = player.peekNext()
            peekPrev = player.peekPrevious()
            delay(400)
        }
    }

    LaunchedEffect(currentSong?.id, currentSong?.path) {
        themeStore.updateCurrent(context, currentSong, baseScheme)
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
        activity?.title = ActivityTitleFormat.format(currentSong)
    }
    LaunchedEffect(peekNext?.id, peekPrev?.id) {
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
    }

    BackHandler(enabled = playerExpanded) { playerExpanded = false }
    BackHandler(enabled = !playerExpanded && detailStack.isNotEmpty()) { popDetail() }

    fun playAlbumFrom(album: AlbumItem, songs: List<Song>, index: Int) {
        val key = albumKey(album.name, album.artist)
        player.setRepeatMode(RepeatMode.COLD)
        player.playSource(
            songs = songs,
            startIndex = index,
            source = ColdSource(
                type = ColdSourceType.ALBUM,
                id = key,
                title = album.name
            )
        )
    }

    fun openAlbumForSong(song: Song) {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val found = library.albums().firstOrNull {
            albumKey(it.name, it.artist) == key
        } ?: library.albums().firstOrNull {
            it.name.equals(song.album, ignoreCase = true)
        }
        if (found != null) {
            playerExpanded = false
            pushDetail(DetailRoute.Album(found))
        } else {
            Toast.makeText(context, "Album not found in library", Toast.LENGTH_SHORT).show()
        }
    }

    fun openArtistForSong(song: Song) {
        val name = song.effectiveAlbumArtist ?: song.artist
        if (name.isNullOrBlank()) {
            Toast.makeText(context, "No artist tag", Toast.LENGTH_SHORT).show()
            return
        }
        val found = library.artists().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: ArtistItem(name = name, trackCount = 0, albumCount = 0, songs = emptyList())
        playerExpanded = false
        pushDetail(DetailRoute.Artist(found))
    }

    CompositionLocalProvider(LocalStatusBarStack provides statusBarStack) {
        ApplyStatusBarStack(statusBarStack)

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = if (edgeToEdgeDetail) Color.Transparent
                else MaterialTheme.colorScheme.background,
                contentWindowInsets = if (edgeToEdgeDetail) {
                    WindowInsets(0, 0, 0, 0)
                } else {
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    )
                },
                topBar = {
                    if (detail == null) {
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
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh library")
                                        }
                                    }
                                }
                                IconButton(onClick = { pushDetail(DetailRoute.Settings) }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
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
                val contentPadding = if (edgeToEdgeDetail) {
                    PaddingValues(bottom = innerPadding.calculateBottomPadding())
                } else {
                    innerPadding
                }
                Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                    when (val d = detail) {
                        is DetailRoute.Album -> {
                            val key = albumKey(d.album.name, d.album.artist)
                            LaunchedEffect(d.album.songs.size, key) {
                                player.updateColdFromSource(d.album.songs, key)
                            }
                            AlbumDetailScreen(
                                album = d.album,
                                nowPlaying = currentSong,
                                isSourceActive = snapshot.isPlayingFromAlbum(key),
                                isPlaying = playing,
                                shuffleEnabled = snapshot.shuffleEnabled,
                                onBack = { popDetail() },
                                onPlayAlbum = { songs, index -> playAlbumFrom(d.album, songs, index) },
                                onTogglePlayPause = { player.togglePlayPause() },
                                onToggleShuffle = { player.toggleShuffle() },
                                onFavorite = {
                                    Toast.makeText(context, "My Stuff coming soon", Toast.LENGTH_SHORT).show()
                                },
                                onOpenArtist = {
                                    val name = d.album.artist ?: return@AlbumDetailScreen
                                    pushDetail(
                                        DetailRoute.Artist(
                                            library.artists().firstOrNull {
                                                it.name.equals(name, ignoreCase = true)
                                            } ?: ArtistItem(name, 0, 0, emptyList())
                                        )
                                    )
                                },
                                onAddSongToQueue = { player.addToHotQueue(it) },
                                onAddAlbumToQueue = { player.addToHotQueue(it) }
                            )
                        }
                        is DetailRoute.Artist -> {
                            val albums = library.albums().filter {
                                it.artist.equals(d.artist.name, ignoreCase = true)
                            }
                            ArtistDetailScreen(
                                artist = d.artist,
                                albums = albums,
                                onBack = { popDetail() },
                                onOpenAlbum = { pushDetail(DetailRoute.Album(it)) },
                                onPlaySongs = { songs, i ->
                                    player.setRepeatMode(RepeatMode.COLD)
                                    player.playSource(
                                        songs, i,
                                        ColdSource(ColdSourceType.ARTIST, d.artist.name ?: "", d.artist.name)
                                    )
                                }
                            )
                        }
                        is DetailRoute.Settings -> SettingsScreen(onBack = { popDetail() })
                        null -> when (topTab) {
                            TopTab.Home -> PlaceholderScreen("Home", "Pin playlists and shortcuts here later.")
                            TopTab.Library -> LibraryScreen(
                                library = library,
                                nowPlaying = currentSong,
                                isPlaybackActive = playing,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onAddAlbumToQueue = { player.addToHotQueue(it) },
                                onOpenAlbum = { pushDetail(DetailRoute.Album(it)) },
                                onOpenArtist = { pushDetail(DetailRoute.Artist(it)) }
                            )
                            TopTab.MyStuff -> PlaceholderScreen(
                                "My Stuff",
                                "Favorites, playlists, and saved albums/artists will live here."
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = playerExpanded,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f),
                enter = slideInVertically(
                    animationSpec = tween(320),
                    initialOffsetY = { fullHeight -> fullHeight }
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    animationSpec = tween(280),
                    targetOffsetY = { fullHeight -> fullHeight }
                ) + fadeOut(animationSpec = tween(180))
            ) {
                NowPlayingScreen(
                    song = currentSong,
                    playing = playing,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    snapshot = snapshot,
                    peekNextSong = peekNext,
                    peekPrevSong = peekPrev,
                    onCollapse = { playerExpanded = false },
                    onToggle = { player.togglePlayPause() },
                    onPrev = { player.skipToPrevious(forceTrackChange = false) },
                    onForcePrev = { player.skipToPrevious(forceTrackChange = true) },
                    onNext = { player.skipToNext() },
                    onSeek = { player.seekTo(it) },
                    onToggleShuffle = { player.toggleShuffle() },
                    onCycleRepeat = { player.cycleRepeatMode() },
                    onPlayItem = { lane, index -> player.playQueueItem(lane, index) },
                    onMoveHot = { f, t -> player.moveHot(f, t) },
                    onMoveCold = { f, t -> player.moveCold(f, t) },
                    onRemoveHot = { player.removeFromHot(it) },
                    onRemoveCold = { player.removeFromCold(it) },
                    onMoveColdToHot = { player.moveColdToHot(it) },
                    onClearHotQueue = { player.clearHotQueue() },
                    onPlayHistorySong = { s -> player.playSource(listOf(s), 0) },
                    onAddToQueue = { player.addToHotQueue(it) },
                    onClearHistory = { player.clearHistory() },
                    onGoToAlbum = { openAlbumForSong(it) },
                    onGoToArtist = { openArtistForSong(it) },
                    onAddToPlaylist = {
                        Toast.makeText(context, "Playlists coming soon", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
