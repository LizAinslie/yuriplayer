package capital.yuri.yuriplayer.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.activities.ui.AddToPlaylistSheet
import capital.yuri.yuriplayer.activities.ui.AlbumDetailScreen
import capital.yuri.yuriplayer.activities.ui.AlbumNavActions
import capital.yuri.yuriplayer.activities.ui.ApplyStatusBarStack
import capital.yuri.yuriplayer.activities.ui.ArtistDetailScreen
import capital.yuri.yuriplayer.activities.ui.ArtistNavActions
import capital.yuri.yuriplayer.activities.ui.EditAlbumMetadataScreen
import capital.yuri.yuriplayer.activities.ui.EditSongMetadataScreen
import capital.yuri.yuriplayer.activities.ui.ExploreScanMenu
import capital.yuri.yuriplayer.activities.ui.ExploreScreen
import capital.yuri.yuriplayer.activities.ui.ExploreSearchTopBar
import capital.yuri.yuriplayer.activities.ui.HomeFeedScreen
import capital.yuri.yuriplayer.activities.ui.LocalAlbumNav
import capital.yuri.yuriplayer.activities.ui.LocalArtistNav
import capital.yuri.yuriplayer.activities.ui.LocalPlaylistNav
import capital.yuri.yuriplayer.activities.ui.LocalSongNav
import capital.yuri.yuriplayer.activities.ui.LocalStatusBarStack
import capital.yuri.yuriplayer.activities.ui.LocalTabBackEnabled
import capital.yuri.yuriplayer.activities.ui.MiniPlayerBar
import capital.yuri.yuriplayer.activities.ui.MyStuffScreen
import capital.yuri.yuriplayer.activities.ui.NowPlayingScreen
import capital.yuri.yuriplayer.activities.ui.PlaylistCoverGlobalHost
import capital.yuri.yuriplayer.activities.ui.PlaylistCoverUi
import capital.yuri.yuriplayer.activities.ui.PlaylistDetailScreen
import capital.yuri.yuriplayer.activities.ui.PlaylistNavActions
import capital.yuri.yuriplayer.activities.ui.SettingsScreen
import capital.yuri.yuriplayer.activities.ui.SongNavActions
import capital.yuri.yuriplayer.activities.ui.StatusBarColorStack
import capital.yuri.yuriplayer.activities.ui.theme.YuriPlayerTheme
import capital.yuri.yuriplayer.ui.TestTags
import capital.yuri.yuriplayer.data.ActivityTitleFormat
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.allCreditsForSong
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.ArtistRole
import capital.yuri.yuriplayer.data.primaryArtistName
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.ColdSourceType
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.RepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    private val libraryIndex: LibraryIndex by inject()
    private val playerController: PlayerController by inject()

    private var isCarMode = false
    private val openPlayerState = mutableStateOf(false)

    private val showAllFilesPrompt = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val readOk = hasReadPermission() || results.any { (perm, granted) ->
            granted && perm in setOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        }
        // Do NOT start a library scan here — that raced playback restore on every
        // cold start. LibraryIndex.bootstrap handles empty / stale later.
        if (readOk) maybePromptAllFilesAccess()
    }

    private fun hasReadPermission(): Boolean {
        val read = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, read) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            // Permissions already granted — do NOT refresh(). Bootstrap decides
            // whether a local scan is needed, and only after playback restore.
            maybePromptAllFilesAccess()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun maybePromptAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return
        if (Environment.isExternalStorageManager()) return
        showAllFilesPrompt.value = true
    }

    fun openAllFilesAccessSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings → Apps → YuriPlayer → Permissions → All files",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        isCarMode = intent?.action == "capital.yuri.yuriplayer.action.CAR_MODE" ||
                intent?.getBooleanExtra("car_mode", false) == true
        openPlayerState.value = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true

        // Bind as early as possible so the first play tap has a live service.
        // Application already started MusicService for restore; this attaches the binder.
        playerController.bind()

        ensurePermissions()

        title = "YuriPlayer"
        enableEdgeToEdge()
        setContent {
            YuriPlayerTheme {
                val openPlayer by openPlayerState
                val needAllFiles by showAllFilesPrompt
                if (needAllFiles) {
                    AlertDialog(
                        onDismissRequest = { showAllFilesPrompt.value = false },
                        title = { Text("Allow file access?") },
                        text = {
                            Text(
                                "To edit song/album tags (and keep years accurate from FLAC files), " +
                                    "YuriPlayer needs All files access. On the next screen, enable " +
                                    "access for YuriPlayer, then return and refresh the library."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showAllFilesPrompt.value = false
                                    openAllFilesAccessSettings()
                                }
                            ) { Text("Open settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAllFilesPrompt.value = false }) {
                                Text("Not now")
                            }
                        }
                    )
                }
                YuriApp(
                    library = libraryIndex,
                    player = playerController,
                    openPlayerInitially = openPlayer,
                    onPlayerOpened = { openPlayerState.value = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            showAllFilesPrompt.value = false
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-bind if we lost the connection while backgrounded
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

private enum class TopTab(val label: String) {
    Home("Home"),
    MyStuff("My Stuff"),
    Explore("Explore")
}

private sealed class DetailRoute {
    data class Album(val album: AlbumItem, val highlightSongKey: String? = null) : DetailRoute()
    data class Artist(val artist: ArtistItem) : DetailRoute()
    data class Playlist(val playlistId: String) : DetailRoute()
    data class EditSong(val song: Song) : DetailRoute()
    data class EditAlbum(val album: AlbumItem) : DetailRoute()
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
    val settings: LibrarySettings = koinInject()
    val enrichment: MetadataEnrichmentService = koinInject()
    val playlistRepo: PlaylistRepository = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    val catalog: CatalogRepository = koinInject()
    val scope = rememberCoroutineScope()
    val baseScheme = MaterialTheme.colorScheme

    val statusBarStack = remember(baseScheme.background) {
        StatusBarColorStack(baseScheme.background)
    }
    LaunchedEffect(baseScheme.background) {
        statusBarStack.replaceBase(baseScheme.background)
    }

    var showNetworkPrompt by remember {
        mutableStateOf(settings.networkMetadataConsent() == null)
    }

    val songCount by library.songs.collectAsState()
    val loading by library.isLoading.collectAsState()
    val colorRev by settings.colorPrefsRevision.collectAsState()

    LaunchedEffect(songCount.size, loading, showNetworkPrompt) {
        if (!showNetworkPrompt &&
            settings.isNetworkMetadataEnabled() &&
            !loading &&
            songCount.isNotEmpty()
        ) {
            enrichment.enrichLibraryAsync()
        }
    }

    if (showNetworkPrompt) {
        AlertDialog(
            onDismissRequest = {
                settings.setNetworkMetadataConsent(false)
                showNetworkPrompt = false
            },
            title = { Text("Find missing artwork?") },
            text = {
                Text(
                    "YuriPlayer can fill in missing album art and release years. " +
                        "This uses the internet. Nothing is uploaded. " +
                        "You can turn this off later in Settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.setNetworkMetadataConsent(true)
                        showNetworkPrompt = false
                        enrichment.enrichLibraryAsync()
                    }
                ) { Text("Allow") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settings.setNetworkMetadataConsent(false)
                        showNetworkPrompt = false
                    }
                ) { Text("Not now") }
            }
        )
    }

    var topTab by remember { mutableStateOf(TopTab.Home) }
    var exploreQuery by rememberSaveable { mutableStateOf("") }
    var playerExpanded by remember { mutableStateOf(false) }
    var tabStacks by remember {
        mutableStateOf(
            mapOf(
                TopTab.Home to emptyList<DetailRoute>(),
                TopTab.MyStuff to emptyList(),
                TopTab.Explore to emptyList()
            )
        )
    }
    var npPlaylistSong by remember { mutableStateOf<Song?>(null) }

    fun stackOf(tab: TopTab) = tabStacks[tab].orEmpty()

    fun pushDetail(route: DetailRoute) {
        val tab = topTab
        tabStacks = tabStacks + (tab to stackOf(tab) + route)
    }

    fun popDetail() {
        val tab = topTab
        val stack = stackOf(tab)
        if (stack.isNotEmpty()) tabStacks = tabStacks + (tab to stack.dropLast(1))
    }

    fun selectTab(tab: TopTab) {
        if (topTab == tab) {
            if (stackOf(tab).isNotEmpty()) {
                tabStacks = tabStacks + (tab to emptyList())
            }
        } else {
            topTab = tab
        }
    }

    val currentStack = stackOf(topTab)
    val detail = currentStack.lastOrNull()
    val previousDetail = currentStack.getOrNull(currentStack.lastIndex - 1)
    val edgeToEdgeDetail = detail is DetailRoute.Album ||
        detail is DetailRoute.Artist ||
        detail is DetailRoute.Playlist

    LaunchedEffect(openPlayerInitially) {
        if (openPlayerInitially) {
            playerExpanded = true
            onPlayerOpened()
        }
    }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    val snapshot by player.snapshot.collectAsState()
    val viewState by player.viewState.collectAsState()
    val currentSong = viewState.song ?: snapshot.currentSong
    val peekNext = viewState.next
    val peekPrev = viewState.previous

    val connected by player.isConnected.collectAsState()

    LaunchedEffect(viewState.song?.songKey, viewState.playing) {
        playing = viewState.playing
    }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (isActive) {
            playing = player.isPlayingNow()
            positionMs = player.getPositionMs()
            durationMs = player.getDurationMs()
            delay(200)
        }
    }

    LaunchedEffect(
        currentSong?.songKey,
        currentSong?.albumArtUri,
        colorRev
    ) {
        val incoming = currentSong
        activity?.title = ActivityTitleFormat.format(incoming)
        if (incoming == null) {
            themeStore.updateCurrent(context, null, baseScheme)
            return@LaunchedEffect
        }
        themeStore.showSong(context, incoming, baseScheme)
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
    }
    LaunchedEffect(peekNext?.id, peekPrev?.id, peekNext?.path, peekPrev?.path) {
        // Wait for the cover slide to promote peek-next; replacing it
        // immediately would snap art and skip the animation.
        delay(350)
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
    }

    PredictiveBackHandler(enabled = playerExpanded) { events ->
        events.collect { }
        playerExpanded = false
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    var backSwipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = !playerExpanded && currentStack.isNotEmpty()) { events ->
        try {
            events.collect { event ->
                backProgress = event.progress
                backSwipeEdge = event.swipeEdge
            }
            popDetail()
        } finally {
            backProgress = 0f
        }
    }

    fun playAlbumFrom(album: AlbumItem, songs: List<Song>, index: Int) {
        val key = albumKey(album.name, album.artist)
        player.setRepeatMode(RepeatMode.COLD)
        player.playSource(
            songs = songs,
            startIndex = index,
            source = ColdSource(
                type = ColdSourceType.ALBUM,
                id = key,
                title = album.displayName
            )
        )
    }

    /** Local-only fallback when catalog has no row yet. */
    fun resolveArtistLocal(name: String): ArtistItem {
        val key = artistKey(name)
        val found = library.artists(taggedOnly = false).firstOrNull {
            artistKey(it.name) == key
        }
        if (found != null) return found
        val tracks = library.songs.value.filter { song ->
            artistKey(song.effectiveAlbumArtist) == key ||
                song.creditArtists.any { artistKey(it) == key }
        }
        val albums = tracks.mapNotNull { it.album }.map { it.lowercase() }.toSet()
        return ArtistItem(
            name = name,
            trackCount = tracks.size,
            albumCount = albums.size,
            songs = tracks
        )
    }

    fun openAlbumResolved(seed: AlbumItem, highlightSongKey: String? = null) {
        playerExpanded = false
        pushDetail(DetailRoute.Album(seed, highlightSongKey))
    }

    fun openArtistResolved(seed: ArtistItem) {
        val stripped = primaryArtistName(seed.name) ?: seed.displayName
        val item = if (stripped.equals(seed.name, ignoreCase = true)) seed
        else seed.copy(name = stripped)
        playerExpanded = false
        pushDetail(DetailRoute.Artist(item))
    }

    fun openAlbumForSong(song: Song) {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val fromLocal = library.albums(taggedOnly = false).firstOrNull {
            albumKey(it.name, it.artist) == key
        } ?: library.albums(taggedOnly = false).firstOrNull {
            it.name.equals(song.album, ignoreCase = true)
        }
        val seed = when {
            fromLocal != null && fromLocal.songs.isNotEmpty() -> fromLocal
            song.hasAlbum -> AlbumItem(
                name = song.album,
                artist = song.effectiveAlbumArtist,
                trackCount = 1,
                songs = listOf(song)
            )
            else -> null
        }
        if (seed != null) openAlbumResolved(seed, song.songKey)
        else Toast.makeText(context, "Album not found in library", Toast.LENGTH_SHORT).show()
    }

    fun openArtistByName(name: String) {
        val resolved = primaryArtistName(name) ?: name
        if (resolved.isBlank()) {
            Toast.makeText(context, "No artist tag", Toast.LENGTH_SHORT).show()
            return
        }
        val key = artistKey(resolved) ?: resolved.lowercase()
        scope.launch {
            val fromCatalog = withContext(Dispatchers.IO) { catalog.artistItemForKey(key, resolved) }
            openArtistResolved(fromCatalog ?: resolveArtistLocal(resolved))
        }
    }

    fun openArtistForSong(song: Song) {
        val credits = allCreditsForSong(song)
        val name = credits.firstOrNull { it.role == ArtistRole.PRIMARY }?.name
            ?: credits.firstOrNull()?.name
            ?: song.effectiveAlbumArtist
            ?: song.artist
        if (name.isNullOrBlank()) {
            Toast.makeText(context, "No artist tag", Toast.LENGTH_SHORT).show()
            return
        }
        openArtistByName(name)
    }

    CompositionLocalProvider(
        LocalStatusBarStack provides statusBarStack,
        LocalSongNav provides SongNavActions(
            openAlbumForSong = { openAlbumForSong(it) },
            openArtistByName = { openArtistByName(it) }
        ),
        LocalAlbumNav provides AlbumNavActions(
            openAlbum = { openAlbumResolved(it) },
            openArtist = { album ->
                val name = album.artist ?: return@AlbumNavActions
                openArtistByName(name)
            },
            startRadio = {
                player.startAlbumRadio(it)
                Toast.makeText(context, "Radio · ${it.displayName}", Toast.LENGTH_SHORT).show()
            },
            addToQueue = {
                player.addToHotQueue(it.songs)
                Toast.makeText(context, "Queued ${it.trackCount} tracks", Toast.LENGTH_SHORT).show()
            },
            editMetadata = {
                playerExpanded = false
                pushDetail(DetailRoute.EditAlbum(it))
            },
            addToMyStuff = { album ->
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.ALBUM,
                        id = albumKey(album.name, album.artist),
                        title = album.displayName,
                        subtitle = album.displayArtist
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        ),
        LocalArtistNav provides ArtistNavActions(
            openArtist = { openArtistResolved(it) },
            openArtistByName = { openArtistByName(it) },
            startRadio = { name ->
                player.startArtistRadio(name)
                Toast.makeText(context, "Radio · $name", Toast.LENGTH_SHORT).show()
            },
            addToMyStuff = { artist ->
                val key = artistKey(artist.name) ?: return@ArtistNavActions
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.ARTIST,
                        id = key,
                        title = artist.displayName,
                        subtitle = "Artist"
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            }
        ),
        LocalPlaylistNav provides PlaylistNavActions(
            openPlaylist = { id ->
                playerExpanded = false
                pushDetail(DetailRoute.Playlist(id))
            },
            startRadio = { pl ->
                if (pl.songs.isEmpty()) {
                    Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                } else {
                    player.startPlaylistRadio(pl.songs, pl.name)
                    Toast.makeText(context, "Radio · ${pl.name}", Toast.LENGTH_SHORT).show()
                }
            },
            addToMyStuff = { pl ->
                pinStore.addEntry(
                    StuffPin(
                        kind = StuffPinKind.PLAYLIST,
                        id = pl.id,
                        title = pl.name,
                        subtitle = "Playlist"
                    )
                )
                Toast.makeText(context, "Added to My Stuff", Toast.LENGTH_SHORT).show()
            },
            // Root default: open multi-cover picker (public + secret slots).
            changeCover = { playlistId -> PlaylistCoverUi.open(playlistId) }
        )
    ) {
        ApplyStatusBarStack(statusBarStack)

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = if (edgeToEdgeDetail) Color.Transparent
                else MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
                bottomBar = {
                    Column(Modifier.fillMaxWidth()) {
                        MiniPlayerBar(
                            song = currentSong,
                            playing = playing,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onToggle = { player.togglePlayPause() },
                            onExpand = { playerExpanded = true }
                        )
                        NavigationBar {
                            NavigationBarItem(
                                selected = topTab == TopTab.Home,
                                onClick = { selectTab(TopTab.Home) },
                                modifier = Modifier.testTag(TestTags.TAB_HOME),
                                icon = {
                                    Icon(
                                        if (topTab == TopTab.Home) Icons.Filled.Home else Icons.Outlined.Home,
                                        contentDescription = "Home"
                                    )
                                },
                                label = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = topTab == TopTab.MyStuff,
                                onClick = { selectTab(TopTab.MyStuff) },
                                modifier = Modifier.testTag(TestTags.TAB_MY_STUFF),
                                icon = {
                                    Icon(
                                        if (topTab == TopTab.MyStuff) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "My Stuff"
                                    )
                                },
                                label = { Text("My Stuff") }
                            )
                            NavigationBarItem(
                                selected = topTab == TopTab.Explore,
                                onClick = { selectTab(TopTab.Explore) },
                                modifier = Modifier.testTag(TestTags.TAB_EXPLORE),
                                icon = {
                                    Icon(
                                        if (topTab == TopTab.Explore) Icons.Filled.Search else Icons.Outlined.Search,
                                        contentDescription = "Explore"
                                    )
                                },
                                label = { Text("Explore") }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                val contentPadding = PaddingValues(
                    bottom = innerPadding.calculateBottomPadding()
                )
                Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                    CompositionLocalProvider(
                        LocalTabBackEnabled provides currentStack.isEmpty()
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (topTab == TopTab.Explore) {
                                ExploreSearchTopBar(
                                    query = exploreQuery,
                                    onQueryChange = { exploreQuery = it }
                                ) {
                                    ExploreScanMenu()
                                    IconButton(
                                        onClick = { pushDetail(DetailRoute.Settings) },
                                        modifier = Modifier.testTag(TestTags.SETTINGS)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            } else {
                                TopAppBar(
                                    title = {
                                        Text(
                                            topTab.label,
                                            modifier = if (topTab == TopTab.MyStuff) {
                                                Modifier.testTag(TestTags.CATALOG_TITLE)
                                            } else {
                                                Modifier
                                            }
                                        )
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = { pushDetail(DetailRoute.Settings) },
                                            modifier = Modifier.testTag(TestTags.SETTINGS)
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                                        }
                                    }
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (topTab) {
                            TopTab.Home -> HomeFeedScreen(
                                library = library,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onOpenAlbum = { openAlbumResolved(it) },
                                onOpenArtist = { openArtistResolved(it) },
                                onOpenPlaylist = { pl: Playlist ->
                                    pushDetail(DetailRoute.Playlist(pl.id))
                                },
                                onOpenSongAlbum = { openAlbumForSong(it) }
                            )
                            TopTab.MyStuff -> MyStuffScreen(
                                library = library,
                                nowPlaying = currentSong,
                                isPlaybackActive = playing,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onOpenAlbum = { openAlbumResolved(it) },
                                onOpenArtist = { openArtistResolved(it) },
                                onOpenPlaylist = { pl: Playlist ->
                                    pushDetail(DetailRoute.Playlist(pl.id))
                                },
                                onOpenSongAlbum = { openAlbumForSong(it) }
                            )
                            TopTab.Explore -> ExploreScreen(
                                query = exploreQuery,
                                onQueryChange = { exploreQuery = it },
                                nowPlaying = currentSong,
                                isPlaybackActive = playing,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onOpenAlbum = { album -> openAlbumResolved(album) },
                                onOpenArtist = { artist -> openArtistResolved(artist) },
                                onOpenPlaylist = { pl: Playlist ->
                                    pushDetail(DetailRoute.Playlist(pl.id))
                                }
                            )
                        }
                            }
                        }
                    }

                    val mediaDetailHost = @Composable { d: DetailRoute ->
                    when (d) {
                        is DetailRoute.Album -> {
                            val key = albumKey(d.album.name, d.album.artist)
                            var resolvedAlbum by remember(key) { mutableStateOf(d.album) }
                            AlbumDetailScreen(
                                album = d.album,
                                nowPlaying = currentSong,
                                isSourceActive = snapshot.isPlayingFromAlbum(key),
                                isPlaying = playing,
                                shuffleEnabled = snapshot.shuffleEnabled,
                                highlightSongKey = d.highlightSongKey,
                                onBack = { popDetail() },
                                onPlayAlbum = { songs, index ->
                                    val item = resolvedAlbum.copy(
                                        songs = songs,
                                        trackCount = songs.size
                                    )
                                    playAlbumFrom(item, songs, index)
                                },
                                onTogglePlayPause = { player.togglePlayPause() },
                                onToggleShuffle = { player.toggleShuffle() },
                                onFavorite = {},
                                onOpenArtist = {
                                    val name = primaryArtistName(resolvedAlbum.artist)
                                        ?: resolvedAlbum.artist
                                        ?: return@AlbumDetailScreen
                                    openArtistByName(name)
                                },
                                onEditAlbum = { pushDetail(DetailRoute.EditAlbum(resolvedAlbum)) },
                                onEditSong = { pushDetail(DetailRoute.EditSong(it)) },
                                onAddSongToQueue = { player.addToHotQueue(it) },
                                onAddAlbumToQueue = { songs -> player.addToHotQueue(songs) },
                                onStartRadio = { player.startAlbumRadio(resolvedAlbum) },
                                onExpanded = { expanded ->
                                    if (expanded.songs.size >= resolvedAlbum.songs.size) {
                                        resolvedAlbum = expanded
                                        player.updateColdFromSource(expanded.songs, key)
                                        if (settings.isNetworkMetadataEnabled()) {
                                            enrichment.enrichAlbumAsync(expanded)
                                        }
                                    }
                                }
                            )
                        }
                        is DetailRoute.Artist -> {
                            val artistName = primaryArtistName(d.artist.name) ?: d.artist.displayName
                            val aKey = artistKey(artistName) ?: artistName.lowercase()
                            var liveArtist by remember(aKey) { mutableStateOf(d.artist) }
                            var albums by remember(aKey) { mutableStateOf<List<AlbumItem>>(emptyList()) }
                            var appearsOn by remember(aKey) { mutableStateOf<List<AlbumItem>>(emptyList()) }
                            var albumsLoading by remember(aKey) { mutableStateOf(true) }
                            var appearsOnLoading by remember(aKey) { mutableStateOf(true) }
                            LaunchedEffect(aKey) {
                                albumsLoading = true
                                appearsOnLoading = true
                                val localAlbums = library.albums(taggedOnly = false).filter {
                                    artistKey(it.artist) == aKey
                                }
                                if (localAlbums.isNotEmpty()) {
                                    albums = localAlbums
                                    albumsLoading = false
                                }
                                coroutineScope {
                                    val fromCatalogDef = async(Dispatchers.IO) {
                                        catalog.artistItemForKey(aKey, artistName)
                                    }
                                    val catalogAlbumsDef = async(Dispatchers.IO) {
                                        catalog.albumItemsForArtist(aKey, artistName)
                                    }
                                    val guestDef = async(Dispatchers.IO) {
                                        catalog.appearsOnAlbumItems(aKey, artistName)
                                    }
                                    val catalogAlbums = catalogAlbumsDef.await()
                                    val guestAlbums = guestDef.await()
                                    val fromCatalog = fromCatalogDef.await()
                                    liveArtist = (fromCatalog ?: d.artist).let { base ->
                                        val name = primaryArtistName(base.name)
                                            ?: primaryArtistName(artistName)
                                            ?: base.name
                                        val albumN = catalogAlbums.size.coerceAtLeast(localAlbums.size)
                                        val trackN = catalogAlbums.sumOf { it.trackCount }
                                            .coerceAtLeast(localAlbums.sumOf { it.trackCount })
                                            .coerceAtLeast(base.trackCount)
                                        base.copy(
                                            name = name,
                                            albumCount = albumN.coerceAtLeast(base.albumCount),
                                            trackCount = trackN
                                        )
                                    }
                                    albums = when {
                                        catalogAlbums.isNotEmpty() -> catalogAlbums
                                        localAlbums.isNotEmpty() -> localAlbums
                                        else -> emptyList()
                                    }
                                    appearsOn = guestAlbums
                                    albumsLoading = false
                                    appearsOnLoading = false
                                    if (settings.isNetworkMetadataEnabled()) {
                                        albums.forEach { enrichment.enrichAlbumAsync(it) }
                                        appearsOn.forEach { enrichment.enrichAlbumAsync(it) }
                                    }
                                }
                            }
                            ArtistDetailScreen(
                                artist = liveArtist,
                                albums = albums,
                                appearsOn = appearsOn,
                                albumsLoading = albumsLoading,
                                appearsOnLoading = appearsOnLoading,
                                expectedAlbumCount = liveArtist.albumCount,
                                expectedAppearsOnCount = 6,
                                onBack = { popDetail() },
                                onOpenAlbum = { openAlbumResolved(it) },
                                onPlaySongs = { songs, i ->
                                    player.setRepeatMode(RepeatMode.COLD)
                                    player.playSource(
                                        songs, i,
                                        ColdSource(
                                            ColdSourceType.ARTIST,
                                            liveArtist.name ?: "",
                                            liveArtist.displayName
                                        )
                                    )
                                },
                                onStartRadio = {
                                    player.startArtistRadio(
                                        liveArtist.name ?: liveArtist.displayName
                                    )
                                },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onArtistMerged = { merged ->
                                    popDetail()
                                    openArtistResolved(merged)
                                }
                            )
                        }
                        is DetailRoute.Playlist -> {
                            val livePl by playlistRepo.observePlaylist(d.playlistId)
                                .collectAsState(initial = null)
                            LaunchedEffect(livePl?.songs?.size, d.playlistId) {
                                val songs = livePl?.songs.orEmpty()
                                if (songs.isNotEmpty()) {
                                    player.updateColdFromSource(songs, d.playlistId)
                                }
                            }
                            PlaylistDetailScreen(
                                playlistId = d.playlistId,
                                nowPlaying = currentSong,
                                isSourceActive = snapshot.isPlayingFromPlaylist(d.playlistId),
                                isPlaying = playing,
                                isPlaybackActive = playing,
                                onBack = { popDetail() },
                                onPlay = { songs, i ->
                                    val title = livePl?.name ?: "Playlist"
                                    player.setRepeatMode(RepeatMode.COLD)
                                    player.playSource(
                                        songs, i,
                                        ColdSource(
                                            type = ColdSourceType.PLAYLIST,
                                            id = d.playlistId,
                                            title = title
                                        )
                                    )
                                },
                                onTogglePlayPause = { player.togglePlayPause() },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onStartRadio = {
                                    val songs = livePl?.songs.orEmpty()
                                    if (songs.isNotEmpty()) {
                                        player.startPlaylistRadio(songs, livePl?.name)
                                    }
                                }
                            )
                        }
                        is DetailRoute.EditSong -> {
                            EditSongMetadataScreen(
                                song = d.song,
                                onBack = { popDetail() },
                                onSaved = {}
                            )
                        }
                        is DetailRoute.EditAlbum -> {
                            EditAlbumMetadataScreen(
                                album = d.album,
                                onBack = { popDetail() },
                                onSaved = {}
                            )
                        }
                        is DetailRoute.Settings -> SettingsScreen(onBack = { popDetail() })
                    }
                    }

                    if (previousDetail != null) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            mediaDetailHost(previousDetail)
                        }
                    }
                    if (detail != null) {
                        val progress = backProgress
                        val edge = backSwipeEdge
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val dir = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                                    translationX = dir * progress * 48.dp.toPx()
                                    val s = 1f - (0.06f * progress)
                                    scaleX = s
                                    scaleY = s
                                    alpha = 1f - (0.12f * progress)
                                    clip = true
                                    shadowElevation = 16f * (1f - progress)
                                    transformOrigin = TransformOrigin.Center
                                },
                            color = MaterialTheme.colorScheme.background
                        ) {
                            mediaDetailHost(detail)
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
                    onSeekFraction = { player.seekToFraction(it) },
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
                    onAddToPlaylist = { song -> npPlaylistSong = song }
                )
            }

            npPlaylistSong?.let { song ->
                AddToPlaylistSheet(
                    songs = listOf(song),
                    onDismiss = { npPlaylistSong = null }
                )
            }

            // Multi-cover picker (public + secret) — works from detail and from sheets.
            PlaylistCoverGlobalHost()
        }
    }
}
