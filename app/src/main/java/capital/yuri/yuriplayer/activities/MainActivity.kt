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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import capital.yuri.yuriplayer.activities.ui.ExploreScreen
import capital.yuri.yuriplayer.activities.ui.LocalAlbumNav
import capital.yuri.yuriplayer.activities.ui.LocalArtistNav
import capital.yuri.yuriplayer.activities.ui.LocalPlaylistNav
import capital.yuri.yuriplayer.activities.ui.LocalSongNav
import capital.yuri.yuriplayer.activities.ui.LocalStatusBarStack
import capital.yuri.yuriplayer.activities.ui.MiniPlayerBar
import capital.yuri.yuriplayer.activities.ui.MyStuffScreen
import capital.yuri.yuriplayer.activities.ui.NowPlayingScreen
import capital.yuri.yuriplayer.activities.ui.PlaylistDetailScreen
import capital.yuri.yuriplayer.activities.ui.PlaylistNavActions
import capital.yuri.yuriplayer.activities.ui.SettingsScreen
import capital.yuri.yuriplayer.activities.ui.SongNavActions
import capital.yuri.yuriplayer.activities.ui.StatusBarColorStack
import capital.yuri.yuriplayer.activities.ui.theme.YuriPlayerTheme
import capital.yuri.yuriplayer.data.ActivityTitleFormat
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
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
import capital.yuri.yuriplayer.data.artistKey
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

private enum class TopTab(val label: String, val icon: ImageVector) {
    MyStuff("My Stuff", Icons.Default.Favorite),
    Explore("Explore", Icons.Default.Search)
}

private sealed class DetailRoute {
    data class Album(val album: AlbumItem) : DetailRoute()
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
            title = { Text("Online album metadata?") },
            text = {
                Text(
                    "YuriPlayer can look up missing release years and album art " +
                        "from MusicBrainz and the Cover Art Archive (e.g. VOIDSTAR). " +
                        "This uses the internet. Nothing is uploaded — only public " +
                        "catalog searches. You can change this later in Settings."
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

    var topTab by remember { mutableStateOf(TopTab.MyStuff) }
    var exploreRescanKey by remember { mutableStateOf(0) }
    var playerExpanded by remember { mutableStateOf(false) }
    var detailStack by remember { mutableStateOf<List<DetailRoute>>(emptyList()) }
    var npPlaylistSong by remember { mutableStateOf<Song?>(null) }

    fun pushDetail(route: DetailRoute) {
        detailStack = detailStack + route
    }

    fun popDetail() {
        if (detailStack.isNotEmpty()) detailStack = detailStack.dropLast(1)
    }

    val detail = detailStack.lastOrNull()
    val edgeToEdgeDetail = detail is DetailRoute.Album ||
        detail is DetailRoute.Artist ||
        detail is DetailRoute.Playlist

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
            delay(250)
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
                title = album.displayName
            )
        )
    }

    fun resolveArtist(name: String): ArtistItem {
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

    fun openAlbumForSong(song: Song) {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val found = library.albums(taggedOnly = false).firstOrNull {
            albumKey(it.name, it.artist) == key
        } ?: library.albums(taggedOnly = false).firstOrNull {
            it.name.equals(song.album, ignoreCase = true)
        }
        if (found != null) {
            playerExpanded = false
            pushDetail(DetailRoute.Album(found))
        } else if (song.hasAlbum) {
            // Synthetic album from the single track (e.g. remote-only before full reload)
            playerExpanded = false
            pushDetail(
                DetailRoute.Album(
                    AlbumItem(
                        name = song.album,
                        artist = song.effectiveAlbumArtist,
                        trackCount = 1,
                        songs = listOf(song)
                    )
                )
            )
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
        playerExpanded = false
        pushDetail(DetailRoute.Artist(resolveArtist(name)))
    }

    fun openArtistByName(name: String) {
        if (name.isBlank()) {
            Toast.makeText(context, "No artist tag", Toast.LENGTH_SHORT).show()
            return
        }
        playerExpanded = false
        pushDetail(DetailRoute.Artist(resolveArtist(name)))
    }

    CompositionLocalProvider(
        LocalStatusBarStack provides statusBarStack,
        LocalSongNav provides SongNavActions(
            openAlbumForSong = { openAlbumForSong(it) },
            openArtistByName = { openArtistByName(it) }
        ),
        LocalAlbumNav provides AlbumNavActions(
            openAlbum = {
                playerExpanded = false
                pushDetail(DetailRoute.Album(it))
            },
            openArtist = { album ->
                val name = album.artist ?: return@AlbumNavActions
                playerExpanded = false
                pushDetail(DetailRoute.Artist(resolveArtist(name)))
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
            openArtist = {
                playerExpanded = false
                pushDetail(DetailRoute.Artist(it))
            },
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
            }
        )
    ) {
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
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
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
                                if (topTab == TopTab.Explore) {
                                    val libLoading by library.isLoading.collectAsState()
                                    IconButton(onClick = {
                                        library.refresh()
                                        exploreRescanKey++
                                        if (settings.isNetworkMetadataEnabled()) {
                                            enrichment.enrichLibraryAsync()
                                        }
                                    }) {
                                        if (libLoading) {
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
                            val liveAlbum = library.albums(taggedOnly = false).firstOrNull {
                                albumKey(it.name, it.artist) == key
                            } ?: d.album
                            LaunchedEffect(liveAlbum.songs.size, key) {
                                player.updateColdFromSource(liveAlbum.songs, key)
                                if (settings.isNetworkMetadataEnabled()) {
                                    enrichment.enrichAlbumAsync(liveAlbum)
                                }
                            }
                            AlbumDetailScreen(
                                album = liveAlbum,
                                nowPlaying = currentSong,
                                isSourceActive = snapshot.isPlayingFromAlbum(key),
                                isPlaying = playing,
                                shuffleEnabled = snapshot.shuffleEnabled,
                                onBack = { popDetail() },
                                onPlayAlbum = { songs, index -> playAlbumFrom(liveAlbum, songs, index) },
                                onTogglePlayPause = { player.togglePlayPause() },
                                onToggleShuffle = { player.toggleShuffle() },
                                onFavorite = {},
                                onOpenArtist = {
                                    val name = liveAlbum.artist ?: return@AlbumDetailScreen
                                    pushDetail(DetailRoute.Artist(resolveArtist(name)))
                                },
                                onEditAlbum = { pushDetail(DetailRoute.EditAlbum(liveAlbum)) },
                                onEditSong = { pushDetail(DetailRoute.EditSong(it)) },
                                onAddSongToQueue = { player.addToHotQueue(it) },
                                onAddAlbumToQueue = { player.addToHotQueue(it) },
                                onStartRadio = { player.startAlbumRadio(liveAlbum) }
                            )
                        }
                        is DetailRoute.Artist -> {
                            val albums = library.albums(taggedOnly = false).filter {
                                it.artist.equals(d.artist.name, ignoreCase = true)
                            }
                            LaunchedEffect(d.artist.name, albums.size) {
                                if (settings.isNetworkMetadataEnabled()) {
                                    albums.forEach { enrichment.enrichAlbumAsync(it) }
                                }
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
                                        ColdSource(
                                            ColdSourceType.ARTIST,
                                            d.artist.name ?: "",
                                            d.artist.displayName
                                        )
                                    )
                                },
                                onStartRadio = {
                                    player.startArtistRadio(d.artist.name ?: d.artist.displayName)
                                },
                                onAddToQueue = { player.addToHotQueue(it) }
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
                        null -> when (topTab) {
                            TopTab.MyStuff -> MyStuffScreen(
                                library = library,
                                nowPlaying = currentSong,
                                isPlaybackActive = playing,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onOpenAlbum = { pushDetail(DetailRoute.Album(it)) },
                                onOpenArtist = { pushDetail(DetailRoute.Artist(it)) },
                                onOpenPlaylist = { pl: Playlist ->
                                    pushDetail(DetailRoute.Playlist(pl.id))
                                }
                            )
                            TopTab.Explore -> ExploreScreen(
                                nowPlaying = currentSong,
                                isPlaybackActive = playing,
                                onPlay = { songs, index -> player.playSource(songs, index) },
                                onAddToQueue = { player.addToHotQueue(it) },
                                onOpenAlbum = { album ->
                                    playerExpanded = false
                                    pushDetail(DetailRoute.Album(album))
                                },
                                onOpenArtist = { artist ->
                                    playerExpanded = false
                                    pushDetail(DetailRoute.Artist(artist))
                                },
                                forceRescanKey = exploreRescanKey
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
        }
    }
}
