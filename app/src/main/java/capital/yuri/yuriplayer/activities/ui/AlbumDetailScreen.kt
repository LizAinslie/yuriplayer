package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject
import kotlin.math.sqrt

/** Expanded header content height (below status bar). */
private val ExpandedHeaderBody = 400.dp
private val CollapsedBarHeight = 56.dp
/** How far the album→default fade extends below the header bottom. */
private val GradientFadeLength = 220.dp

@OptIn(ExperimentalMaterial3Api::class)
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
    onAddSongToQueue: (Song) -> Unit,
    onAddAlbumToQueue: (List<Song>) -> Unit
) {
    val context = LocalContext.current
    val themeService: ThemeService = koinInject()
    val base = MaterialTheme.colorScheme
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    val collapseRangePx = with(density) { (ExpandedHeaderBody - CollapsedBarHeight).toPx() }
    var collapsePx by remember { mutableFloatStateOf(0f) }
    val f = (collapsePx / collapseRangePx).coerceIn(0f, 1f)

    // Height collapses faster than linear so space under the art snaps up as
    // the art fades (sqrt curve: ~half height gone by f=0.25).
    val heightF = sqrt(f.toDouble()).toFloat()
    val headerBodyH = ExpandedHeaderBody * (1f - heightF) + CollapsedBarHeight * heightF

    val nestedScroll = remember(collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                val delta = available.y
                if (delta < 0f && collapsePx < collapseRangePx) {
                    val consumed = (-delta).coerceAtMost(collapseRangePx - collapsePx)
                    collapsePx += consumed
                    return androidx.compose.ui.geometry.Offset(0f, -consumed)
                }
                if (delta > 0f && collapsePx > 0f &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    val consumed = delta.coerceAtMost(collapsePx)
                    collapsePx -= consumed
                    return androidx.compose.ui.geometry.Offset(0f, consumed)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    LaunchedEffect(album.name, album.artist) {
        themeColors = themeService.themeFromSong(context, album.songs.firstOrNull(), base).colors
        collapsePx = 0f
    }

    val discs = remember(album.songs) { groupByDisc(album.songs) }
    val multiDisc = discs.size > 1 || discs.keys.any { it != null && it > 1 }
    val scheme = playerColorScheme(themeColors, base)
    val albumBg = scheme.background
    val defaultBg = base.background

    val releaseYear = remember(album.songs) {
        album.songs.mapNotNull { it.year }.maxOrNull()
    }
    val releaseType = guessReleaseType(album.trackCount)
    val metaLine = buildString {
        append(releaseType)
        if (releaseYear != null) append(" · $releaseYear")
        append(" · ${formatTrackCount(album.trackCount)}")
    }

    val showPause = isSourceActive && isPlaying
    val onPrimary = {
        if (isSourceActive) onTogglePlayPause()
        else onPlayAlbum(album.songs, 0)
    }

    val fadePx = with(density) { GradientFadeLength.toPx() }

    // Pushes onto the app-wide status-bar stack; pops on leave so library
    // and now-playing handoffs can't leave a stale tint behind.
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
                        // Hero fades with height so they disappear together
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = (1f - heightF * 1.6f).coerceIn(0f, 1f)
                                }
                        ) {
                            SpotifyAlbumHero(
                                album = album,
                                metaLine = metaLine,
                                showPause = showPause,
                                shuffleEnabled = shuffleEnabled,
                                onPrimary = onPrimary,
                                onToggleShuffle = onToggleShuffle,
                                onFavorite = onFavorite,
                                onMore = { showMenu = true },
                                onOpenArtist = onOpenArtist
                            )
                        }

                        if (heightF > 0.2f) {
                            CollapsedSpotifyBar(
                                album = album,
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
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    discs.forEach { (disc, tracks) ->
                        if (multiDisc) {
                            item(key = "disc-$disc") {
                                DiscSectionHeader(discNumber = disc ?: 1)
                            }
                        }
                        itemsIndexed(
                            tracks,
                            key = { _, s -> "${s.id}-${s.path}" }
                        ) { _, song ->
                            val globalIndex = album.songs.indexOfFirst {
                                (it.path != null && it.path == song.path) || it.id == song.id
                            }.coerceAtLeast(0)
                            SwipeAddSongRow(
                                song = song,
                                onClick = { onPlayAlbum(album.songs, globalIndex) },
                                onSwipeAdd = {
                                    onAddSongToQueue(song)
                                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                },
                                showTrackNumber = true,
                                isPlaying = song.isSameAs(nowPlaying),
                                isPlaybackActive = isPlaying,
                                transparentSurface = true
                            )
                        }
                    }
                }
            }

            if (showMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showMenu = false },
                    sheetState = rememberModalBottomSheetState()
                ) {
                    MediaSheetHeader(
                        song = album.songs.firstOrNull(),
                        title = album.displayName,
                        subtitle = album.displayArtist
                    )
                    MediaSheetItem(
                        label = "Add to queue",
                        onClick = {
                            onAddAlbumToQueue(album.songs)
                            Toast.makeText(
                                context,
                                "Queued ${formatTrackCount(album.songs.size)}",
                                Toast.LENGTH_SHORT
                            ).show()
                            showMenu = false
                        }
                    )
                    MediaSheetItem(
                        label = "Go to artist",
                        onClick = {
                            showMenu = false
                            onOpenArtist()
                        }
                    )
                    MediaSheetItem(
                        label = "Add to playlist",
                        onClick = {
                            showMenu = false
                            Toast.makeText(context, "Playlists coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )
                    MediaSheetBottomPad()
                }
            }
        }
    }
}

@Composable
private fun SpotifyAlbumHero(
    album: AlbumItem,
    metaLine: String,
    showPause: Boolean,
    shuffleEnabled: Boolean,
    onPrimary: () -> Unit,
    onToggleShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onOpenArtist: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 36.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArt(
            song = album.songs.firstOrNull(),
            size = 200.dp,
            corner = 8.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = album.displayName,
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
                    .clickable(onClick = onOpenArtist)
                    .padding(vertical = 2.dp)
            ) {
                AlbumArt(
                    song = album.songs.firstOrNull(),
                    size = 24.dp,
                    corner = 12.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
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
                IconButton(onClick = onFavorite) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        contentDescription = "Add to My Stuff",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                    )
                }
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

                IconButton(
                    onClick = onPrimary,
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (showPause) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
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
        IconButton(
            onClick = onPrimary,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (showPause) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
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
    return grouped.toSortedMap(compareBy { it ?: 1 })
}

private fun guessReleaseType(trackCount: Int): String = when {
    trackCount <= 3 -> "Single"
    trackCount <= 8 -> "EP"
    else -> "Album"
}
