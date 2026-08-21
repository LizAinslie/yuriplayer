package capital.yuri.yuriplayer.activities.ui

import MarqueeText
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.albumTrackOrder
import capital.yuri.yuriplayer.data.AlbumLog
import capital.yuri.yuriplayer.data.dedupeAlbumPageTracks
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.primaryArtistName
import capital.yuri.yuriplayer.data.resolveAlbumItem
import capital.yuri.yuriplayer.data.theme.ArtColorSurface
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.SongRowSkeleton
import capital.yuri.yuriplayer.ui.formatTrackCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.sqrt

private val ExpandedHeaderBody = 420.dp
private val CollapsedBarHeight = 56.dp
private val GradientFadeLength = 220.dp

@Composable
private fun CircularPlayButton(
    showPause: Boolean,
    onClick: () -> Unit,
    size: Dp = 52.dp,
    iconSize: Dp = 28.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .requiredSize(size)
            .aspectRatio(1f),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (showPause) "Pause" else "Play",
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
    nowPlaying: Song? = null,
    isSourceActive: Boolean = false,
    isPlaying: Boolean = false,
    shuffleEnabled: Boolean = false,
    onBack: () -> Unit,
    onPlayAlbum: (List<Song>, Int) -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onOpenArtist: () -> Unit = {},
    onEditAlbum: () -> Unit = {},
    onEditSong: (Song) -> Unit = {},
    onAddSongToQueue: (Song) -> Unit,
    onAddAlbumToQueue: (List<Song>) -> Unit,
    onStartRadio: () -> Unit = {},
    onExpanded: (AlbumItem) -> Unit = {},
    highlightSongKey: String? = null
) {
    val context = LocalContext.current
    val themeService: ThemeService = koinInject()
    val enrichment: MetadataEnrichmentService = koinInject()
    val settings: LibrarySettings = koinInject()
    val artCache: AlbumArtCache = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    val catalog: CatalogRepository = koinInject()
    val catalogDao: CatalogDao = koinInject()
    val library: LibraryIndex = koinInject()
    val entries by pinStore.entries.collectAsState()
    val coverGen by enrichment.coverGeneration.collectAsState()
    val colorRev by settings.colorPrefsRevision.collectAsState()
    val base = MaterialTheme.colorScheme
    var themeColors by remember {
        mutableStateOf(
            album.songs.firstOrNull()?.let { seed ->
                themeService.peekCached(artCache.artKey(seed), ArtColorSurface.COVER)
            } ?: fallbackPlayerColors(base)
        )
    }
    var showMenu by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    val albumKeyStr = albumKey(album.name, album.artist)
    val stableKey = albumKey(album.name, primaryArtistName(album.artist) ?: album.artist)
    val albumSaved = remember(entries, albumKeyStr, stableKey) {
        pinStore.contains(StuffPinKind.ALBUM, albumKeyStr) ||
            pinStore.contains(StuffPinKind.ALBUM, stableKey)
    }

    var liveAlbum by remember(stableKey) { mutableStateOf(album) }
    var expanding by remember(stableKey) { mutableStateOf(true) }
    var expectedTrackCount by remember(stableKey) {
        mutableIntStateOf(album.trackCount.coerceAtLeast(album.songs.size))
    }
    LaunchedEffect(stableKey) {
        AlbumLog.i(
            album.name,
            "PAGE open stableKey='$stableKey' albumKey='$albumKeyStr' seed=${album.songs.size} artist='${album.artist}'"
        )
        var current = album
        fun publish(next: AlbumItem, stage: String) {
            val union = dedupeAlbumPageTracks(current.songs + next.songs + album.songs)
            val before = current.songs.size
            val songs = if (union.size < before && before > 1) {
                AlbumLog.w(album.name, "PAGE $stage would shrink $before → ${union.size}")
                dedupeAlbumPageTracks(current.songs + album.songs + next.songs)
            } else union
            current = next.copy(
                artist = primaryArtistName(next.artist) ?: next.artist,
                songs = songs,
                trackCount = songs.size.coerceAtLeast(next.trackCount)
            )
            liveAlbum = current
            expectedTrackCount = expectedTrackCount.coerceAtLeast(current.trackCount)
            onExpanded(current)
            AlbumLog.i(album.name, "PAGE $stage n=${current.songs.size}")
        }

        expanding = true
        val artistName = primaryArtistName(album.artist) ?: album.artist
        val counted = withContext(Dispatchers.IO) {
            catalog.albumTrackCount(stableKey)
                ?: catalog.albumTrackCount(albumKeyStr)
        }
        if (counted != null) expectedTrackCount = expectedTrackCount.coerceAtLeast(counted)

        val fast = withContext(Dispatchers.IO) {
            catalog.fastAlbumItem(album.name, artistName, stableKey, album.songs)
                ?: catalog.fastAlbumItem(album.name, artistName, albumKeyStr, album.songs)
        }
        if (fast != null) publish(fast, "fast")

        val resolved = withContext(Dispatchers.IO) {
            resolveAlbumItem(
                dao = catalogDao,
                name = album.name,
                artist = artistName,
                seedSongs = current.songs,
                library = library
            )
        }
        if (resolved != null) publish(resolved, "full")
        expanding = false
    }

    val collapseRangePx = with(density) { (ExpandedHeaderBody - CollapsedBarHeight).toPx() }
    var collapsePx by remember { mutableFloatStateOf(0f) }
    val f = (collapsePx / collapseRangePx).coerceIn(0f, 1f)

    val heightF = sqrt(f.toDouble()).toFloat()
    val headerBodyH = ExpandedHeaderBody * (1f - heightF) + CollapsedBarHeight * heightF

    val nestedScroll = remember(collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
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

    LaunchedEffect(album.name, album.artist) {
        collapsePx = 0f
    }

    LaunchedEffect(
        liveAlbum.name,
        liveAlbum.artist,
        liveAlbum.songs.firstOrNull()?.path,
        coverGen,
        colorRev
    ) {
        themeColors = themeService.themeFromSong(
            context = context,
            song = liveAlbum.songs.firstOrNull(),
            base = base,
            forceRefresh = false,
            surface = ArtColorSurface.COVER,
            loadBitmap = false
        ).colors
    }

    val discs = remember(liveAlbum.songs) { groupByDisc(liveAlbum.songs) }
    val multiDisc = discs.size > 1 || discs.keys.any { it != null && it > 1 }
    LaunchedEffect(highlightSongKey, discs, multiDisc) {
        val want = highlightSongKey ?: return@LaunchedEffect
        var index = 0
        for ((_, tracks) in discs) {
            if (multiDisc) index++
            val hit = tracks.indexOfFirst { it.songKey == want }
            if (hit >= 0) {
                listState.animateScrollToItem((index + hit).coerceAtLeast(0))
                return@LaunchedEffect
            }
            index += tracks.size
        }
    }
    val scheme = playerColorScheme(themeColors, base)
    val albumBg = scheme.background
    val defaultBg = base.background

    val releaseYear = remember(liveAlbum.songs, coverGen) {
        liveAlbum.songs.mapNotNull { it.year }.maxOrNull()
    }
    val shownTrackCount = liveAlbum.songs.size.coerceAtLeast(1)
    val releaseType = guessReleaseType(shownTrackCount)
    val metaLine = buildString {
        append(releaseType)
        if (releaseYear != null) append(" · $releaseYear")
        append(" · ${formatTrackCount(shownTrackCount)}")
    }

    val showPause = isSourceActive && isPlaying
    val onPrimary = {
        if (isSourceActive) onTogglePlayPause()
        else onPlayAlbum(liveAlbum.songs, 0)
    }

    val fadePx = with(density) { GradientFadeLength.toPx() }

    ThemedStatusBar(color = albumBg, enabled = true)

    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScroll)
                .background(defaultBg)
                .drawBehind {
                    val headerEnd = headerBodyH.toPx()
                    val solidEnd = headerEnd + with(density) { 48.dp.toPx() }
                    val fadeEnd = solidEnd + fadePx

                    drawRect(
                        color = albumBg,
                        topLeft = Offset.Zero,
                        size = Size(size.width, solidEnd.coerceAtMost(size.height))
                    )

                    if (fadeEnd > solidEnd) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    albumBg,
                                    albumBg.copy(alpha = 0.85f),
                                    albumBg.copy(alpha = 0.45f),
                                    albumBg.copy(alpha = 0.15f),
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
                            SpotifyAlbumHero(
                                album = liveAlbum,
                                metaLine = metaLine,
                                showPause = showPause,
                                shuffleEnabled = shuffleEnabled,
                                albumSaved = albumSaved,
                                onPrimary = onPrimary,
                                onToggleShuffle = onToggleShuffle,
                                onFavorite = {
                                    val now = pinStore.toggleAlbum(liveAlbum)
                                    Toast.makeText(
                                        context,
                                        if (now) "Album + tracks added to My Stuff"
                                        else "Album removed from My Stuff",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onFavorite()
                                },
                                onMore = { showMenu = true },
                                onOpenArtist = onOpenArtist,
                                onCoverLongPress = { showCoverPicker = true }
                            )
                        }

                        if (heightF > 0.2f) {
                            CollapsedSpotifyBar(
                                album = liveAlbum,
                                showPause = showPause,
                                barColor = Color.Transparent,
                                onBack = onBack,
                                onPrimary = onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer {
                                        alpha = ((heightF - 0.2f) / 0.35f).coerceIn(0f, 1f)
                                    }
                            )
                        }

                        if (heightF < 0.5f) {
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
                                    tint = scheme.onBackground
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
                ) {
                    discs.forEach { (disc, tracks) ->
                        if (multiDisc) {
                            item(key = "disc-$disc") {
                                DiscSectionHeader(discNumber = disc ?: 1)
                            }
                        }
                        itemsIndexed(
                            tracks,
                            key = { i, s -> "${s.songKey}#$i#${s.trackNumber}" }
                        ) { _, song ->
                            val globalIndex = liveAlbum.songs.indexOfFirst {
                                it.songKey == song.songKey ||
                                    (it.path != null && it.path == song.path) ||
                                    it.id == song.id
                            }.coerceAtLeast(0)
                            SwipeAddSongRow(
                                song = song,
                                onClick = { onPlayAlbum(liveAlbum.songs, globalIndex) },
                                onSwipeAdd = {
                                    onAddSongToQueue(song)
                                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                },
                                showTrackNumber = true,
                                isPlaying = song.isSameAs(nowPlaying),
                                isPlaybackActive = isPlaying,
                                isHighlighted = highlightSongKey != null &&
                                    (song.songKey == highlightSongKey),
                                transparentSurface = true,
                                showHeart = true,
                                hideGoToAlbum = true,
                                onEditMetadata = { onEditSong(song) }
                            )
                        }
                    }
                    if (expanding && liveAlbum.songs.size < expectedTrackCount) {
                        val extra = (expectedTrackCount - liveAlbum.songs.size).coerceIn(1, 16)
                        items(extra) { SongRowSkeleton() }
                    }
                }
            }

            if (showMenu) {
                AlbumContextSheet(
                    album = liveAlbum,
                    onDismiss = { showMenu = false },
                    onGoToArtist = onOpenArtist,
                    onEditMetadata = onEditAlbum,
                    onAddToQueue = { onAddAlbumToQueue(liveAlbum.songs) },
                    onStartRadio = onStartRadio,
                    onFetchMetadata = {
                        enrichment.enrichAlbumAsync(liveAlbum, force = true)
                        Toast.makeText(
                            context,
                            "Looking up year & cover online\u2026",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            if (showCoverPicker) {
                AlbumCoverPickerHost(
                    album = liveAlbum,
                    onDismiss = { showCoverPicker = false }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SpotifyAlbumHero(
    album: AlbumItem,
    metaLine: String,
    showPause: Boolean,
    shuffleEnabled: Boolean,
    albumSaved: Boolean,
    onPrimary: () -> Unit,
    onToggleShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onOpenArtist: () -> Unit,
    onCoverLongPress: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 36.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = onCoverLongPress
            )
        ) {
            AlbumArt(
                song = album.songs.firstOrNull(),
                size = 200.dp,
                corner = 8.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            MarqueeText(
                text = album.displayName,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(onClick = onOpenArtist, onLongClick = {})
                    .padding(vertical = 2.dp)
            ) {
                AlbumArt(
                    song = album.songs.firstOrNull(),
                    size = 24.dp,
                    corner = 12.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                MarqueeText(
                    text = album.displayArtist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MyStuffHeart(
                    saved = albumSaved,
                    onToggle = onFavorite,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    savedTint = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }

                CircularPlayButton(
                    showPause = showPause,
                    onClick = onPrimary,
                    size = 52.dp,
                    iconSize = 28.dp
                )
            }
        }
    }
}

@Composable
private fun CollapsedSpotifyBar(
    album: AlbumItem,
    showPause: Boolean,
    barColor: Color,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CollapsedBarHeight)
            .background(barColor)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        AlbumArt(song = album.songs.firstOrNull(), size = 40.dp, corner = 4.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = album.displayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            MarqueeText(
                text = album.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CircularPlayButton(
            showPause = showPause,
            onClick = onPrimary,
            size = 40.dp,
            iconSize = 22.dp
        )
    }
}

@Composable
private fun DiscSectionHeader(discNumber: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Disk $discNumber",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
        )
    }
}

private fun groupByDisc(songs: List<Song>): Map<Int?, List<Song>> {
    val grouped = songs.groupBy { it.discNumber }
    return grouped.toSortedMap(compareBy { it ?: 1 }).mapValues { (_, tracks) ->
        tracks.sortedWith(albumTrackOrder())
    }
}

private fun guessReleaseType(trackCount: Int): String = when {
    trackCount <= 3 -> "Single"
    trackCount <= 8 -> "EP"
    else -> "Album"
}
