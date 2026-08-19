package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.SortMode
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.label
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.formatAlbumCount
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class LibrarySection { Songs, Albums, Artists, Untagged }

@Composable
fun SortDropdown(sortMode: SortMode, onSortModeChange: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sort: ${sortMode.label()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Sort options",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label(), style = MaterialTheme.typography.bodyMedium) },
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
fun LibraryScreen(
    library: LibraryIndex,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddAlbumToQueue: (List<Song>) -> Unit = {},
    onOpenAlbum: (AlbumItem) -> Unit = {},
    onOpenArtist: (ArtistItem) -> Unit = {},
    onEditSong: (Song) -> Unit = {},
    onEditAlbum: (AlbumItem) -> Unit = {}
) {
    val allSongs by library.songs.collectAsState()
    val loading by library.isLoading.collectAsState()
    val lastScanned by library.lastScannedAt.collectAsState()
    val error by library.error.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val player: PlayerController = koinInject()

    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var section by remember { mutableStateOf(LibrarySection.Songs) }

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    val taggedSongs = remember(allSongs, sortMode, query) {
        library.search(query, sortMode, taggedOnly = true)
    }
    val untaggedSongs = remember(allSongs, sortMode, query) {
        library.search(query, sortMode, taggedOnly = false)
    }
    val albums = remember(allSongs, query) { library.albums(query, taggedOnly = true) }
    val artists = remember(allSongs, query) { library.artists(query, taggedOnly = true) }

    fun openArtistByName(name: String, seed: Song? = null) {
        val match = library.artists(taggedOnly = false)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: ArtistItem(
                name = name,
                trackCount = seed?.let { 1 } ?: 0,
                albumCount = if (seed?.hasAlbum == true) 1 else 0,
                songs = listOfNotNull(seed)
            )
        onOpenArtist(match)
    }

    fun openAlbumForSong(song: Song) {
        val albumName = song.album?.takeIf { it.isNotBlank() } ?: return
        val artistKey = song.effectiveAlbumArtist
        val match = library.albums(taggedOnly = false).firstOrNull {
            it.name.equals(albumName, ignoreCase = true) &&
                (artistKey == null || it.artist.equals(artistKey, ignoreCase = true))
        } ?: AlbumItem(
            name = albumName,
            artist = artistKey,
            trackCount = 1,
            songs = listOf(song)
        )
        onOpenAlbum(match)
    }

    fun openArtistForAlbum(album: AlbumItem) {
        val name = album.artist?.takeIf { it.isNotBlank() } ?: return
        openArtistByName(name, album.songs.firstOrNull())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Filter songs, albums, artists…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            )
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
            else -> formatTrackCount(allSongs.size)
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        when (section) {
            LibrarySection.Songs -> SongList(
                songs = taggedSongs,
                loading = loading,
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                onPlay = onPlay,
                onAddToQueue = {
                    onAddToQueue(it)
                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                },
                onGoToAlbum = { openAlbumForSong(it) },
                onGoToArtist = { name -> openArtistByName(name) },
                onEditSong = onEditSong,
                onStartRadio = { song ->
                    player.startSongRadio(song)
                    Toast.makeText(context, "Radio · ${song.displayArtist}", Toast.LENGTH_SHORT).show()
                }
            )
            LibrarySection.Untagged -> SongList(
                songs = untaggedSongs,
                loading = loading,
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                onPlay = onPlay,
                onAddToQueue = {
                    onAddToQueue(it)
                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                },
                onGoToAlbum = { openAlbumForSong(it) },
                onGoToArtist = { name -> openArtistByName(name) },
                onEditSong = onEditSong,
                onStartRadio = { song ->
                    player.startSongRadio(song)
                    Toast.makeText(context, "Radio · ${song.displayArtist}", Toast.LENGTH_SHORT).show()
                }
            )
            LibrarySection.Albums -> {
                if (albums.isEmpty()) Text("No albums match.", modifier = Modifier.padding(16.dp))
                else LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(albums, key = { "${it.name}|${it.artist}" }) { album ->
                        SwipeAddAlbumRow(
                            album = album,
                            onClick = { onOpenAlbum(album) },
                            onSwipeAdd = {
                                onAddAlbumToQueue(album.songs)
                                Toast.makeText(
                                    context,
                                    "Queued ${formatTrackCount(album.songs.size)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onGoToArtist = { openArtistForAlbum(album) },
                            onEditMetadata = { onEditAlbum(album) },
                            onStartRadio = {
                                player.startAlbumRadio(album)
                                Toast.makeText(context, "Radio · ${album.displayName}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            LibrarySection.Artists -> {
                if (artists.isEmpty()) Text("No artists match.", modifier = Modifier.padding(16.dp))
                else LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists, key = { it.name?.lowercase() ?: "_" }) { artist ->
                        ArtistRow(artist) { onOpenArtist(artist) }
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
    nowPlaying: Song?,
    isPlaybackActive: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onGoToAlbum: (Song) -> Unit,
    onGoToArtist: (String) -> Unit,
    onEditSong: (Song) -> Unit,
    onStartRadio: (Song) -> Unit = {}
) {
    if (songs.isEmpty() && !loading) {
        Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id to s.path }) { index, song ->
                SwipeAddSongRow(
                    song = song,
                    onClick = { onPlay(songs, index) },
                    onSwipeAdd = { onAddToQueue(song) },
                    showTrackNumber = false,
                    isPlaying = song.isSameAs(nowPlaying),
                    isPlaybackActive = isPlaybackActive,
                    showHeart = true,
                    onGoToAlbum = { onGoToAlbum(song) },
                    onGoToArtist = onGoToArtist,
                    onEditMetadata = { onEditSong(song) },
                    onStartRadio = { onStartRadio(song) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeAddSongRow(
    song: Song,
    onClick: () -> Unit,
    onSwipeAdd: () -> Unit,
    showTrackNumber: Boolean = false,
    isPlaying: Boolean = false,
    isPlaybackActive: Boolean = false,
    transparentSurface: Boolean = false,
    surfaceColor: Color? = null,
    showHeart: Boolean = true,
    hideGoToAlbum: Boolean = false,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    isExplicit: Boolean = false,
    multiSource: Boolean = false,
    sourceOfferings: List<SourceOffering>? = null,
    onPreferSource: ((SourceOffering) -> Unit)? = null
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var showSheet by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val pinStore: MyStuffPinStore = koinInject()
    val entries by pinStore.entries.collectAsState()
    val saved = remember(entries, song.songKey) {
        pinStore.contains(StuffPinKind.SONG, song.songKey)
    }
    val songNav = LocalSongNav.current

    // Always resolve — album / playlist / artist discography rows never passed this before.
    val offerings = rememberSongOfferings(song, sourceOfferings)
    val preferHandler = onPreferSource ?: rememberPreferSourceHandler(song)

    val revealAlpha = (offsetX / (threshold * 0.35f)).coerceIn(0f, 1f)
    val showE = isExplicit || song.isExplicit
    val showMulti = multiSource || CatalogRepository.isMultiSource(offerings)

    val rowBg = when {
        isPlaying -> accent.copy(alpha = 0.18f)
        transparentSurface -> Color.Transparent
        surfaceColor != null -> surfaceColor
        else -> MaterialTheme.colorScheme.surface
    }
    val titleColor = if (isPlaying) accent else onSurface
    val titleWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal
    val context = LocalContext.current
    val subtitleColor = if (isPlaying) accent.copy(alpha = 0.75f)
    else onSurface.copy(alpha = 0.6f)
    val subtitleText = if (showTrackNumber) song.displayArtist
    else "${song.displayArtist} · ${song.displayAlbum}"

    Box(modifier = Modifier.fillMaxWidth()) {
        if (revealAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f * revealAlpha)
                    )
            ) {
                Text(
                    "+ Queue",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent.copy(alpha = revealAlpha),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(rowBg)
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
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showSheet = true }
                )
                .padding(
                    start = if (showTrackNumber) 8.dp else 16.dp,
                    end = 4.dp,
                    top = 10.dp,
                    bottom = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!showTrackNumber) {
                AlbumArt(song = song, size = 40.dp, corner = 4.dp)
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isPlaying -> PlayingIndicator(
                            color = accent,
                            animated = isPlaybackActive
                        )
                        song.trackNumber != null -> Text(
                            text = "${song.trackNumber}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onSurface.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(
                    text = song.displayTitle,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = titleWeight),
                    color = titleColor
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showE || showMulti) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SongBadgeRow(isExplicit = showE, multiSource = showMulti)
                    }
                }
            }
            if (showHeart) {
                MyStuffHeart(
                    saved = saved,
                    onToggle = {
                        val now = pinStore.toggleSong(song)
                        Toast.makeText(
                            context,
                            if (now) "Added to My Stuff" else "Removed from My Stuff",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    tint = onSurface.copy(alpha = 0.45f),
                    savedTint = accent
                )
            }
        }
    }

    if (showSheet) {
        SongContextSheet(
            song = song,
            onDismiss = { showSheet = false },
            hideGoToAlbum = hideGoToAlbum,
            onGoToAlbum = onGoToAlbum ?: { songNav.openAlbumForSong(song) },
            onGoToArtist = onGoToArtist ?: { name -> songNav.openArtistByName(name) },
            onEditMetadata = onEditMetadata,
            onAddToQueue = onSwipeAdd,
            onStartRadio = onStartRadio,
            sourceOfferings = offerings,
            onPreferSource = preferHandler
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeAddAlbumRow(
    album: AlbumItem,
    onClick: () -> Unit,
    onSwipeAdd: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var showSheet by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val revealAlpha = (offsetX / (threshold * 0.35f)).coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxWidth()) {
        if (revealAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f * revealAlpha)
                    )
            ) {
                Text(
                    "+ Queue all",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent.copy(alpha = revealAlpha),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(album.name, album.artist) {
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
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (onGoToArtist != null || onEditMetadata != null || onStartRadio != null) {
                            showSheet = true
                        }
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song = album.songs.firstOrNull(), size = 48.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(text = album.displayName, style = MaterialTheme.typography.bodyLarge)
                MarqueeText(
                    text = "${album.displayArtist} · ${formatTrackCount(album.trackCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }

    if (showSheet) {
        AlbumContextSheet(
            album = album,
            onDismiss = { showSheet = false },
            onGoToArtist = onGoToArtist,
            onEditMetadata = onEditMetadata,
            onAddToQueue = onSwipeAdd,
            onStartRadio = onStartRadio
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumRow(
    album: AlbumItem,
    onClick: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (onGoToArtist != null || onEditMetadata != null || onStartRadio != null) {
                        showSheet = true
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(text = album.displayName, style = MaterialTheme.typography.bodyLarge)
            MarqueeText(
                text = "${album.displayArtist} · ${formatTrackCount(album.trackCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    if (showSheet) {
        AlbumContextSheet(
            album = album,
            onDismiss = { showSheet = false },
            onGoToArtist = onGoToArtist,
            onEditMetadata = onEditMetadata,
            onStartRadio = onStartRadio
        )
    }
}

@Composable
fun ArtistRow(artist: ArtistItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistArt(
            artistName = artist.displayName,
            seedSong = artist.songs.firstOrNull(),
            size = 48.dp,
            circular = true
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(text = artist.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatAlbumCount(artist.albumCount)} · ${formatTrackCount(artist.trackCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
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
