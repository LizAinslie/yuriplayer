package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Approximate expanded hero content height (art + meta + actions). */
private val ExpandedHeroHeight = 430.dp
/** Collapsed sticky bar height. */
private val CollapsedBarHeight = 56.dp
/** Long soft fade under the header into the list. */
private val GradientFadeHeight = 320.dp
/** How early the fade begins inside the expanded hero (pulls vignette up a bit). */
private val ExpandedEarlyStart = 72.dp

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

    val collapseRangePx = with(density) { 280.dp.toPx() }
    val collapseFraction by remember {
        derivedStateOf {
            val idx = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset.toFloat()
            when {
                idx > 0 -> 1f
                else -> (off / collapseRangePx).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(album.name, album.artist) {
        themeColors = themeService.themeFromSong(context, album.songs.firstOrNull(), base).colors
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

    val f = collapseFraction.coerceIn(0f, 1f)
    // Sticky gradient top: under expanded hero (with early start) → under collapsed bar
    val gradientTopDp = ExpandedHeroHeight - ExpandedEarlyStart +
        (CollapsedBarHeight - (ExpandedHeroHeight - ExpandedEarlyStart)) * f

    MaterialTheme(colorScheme = scheme) {
        // Album color through the status-bar region; page body is default below the fade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(albumBg)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Default page color fills under the sticky gradient so the list meshes with mini-player
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                0,
                                with(density) { gradientTopDp.toPx().roundToInt() }
                            )
                        }
                        .background(defaultBg)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item(key = "hero") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(albumBg)
                        ) {
                            SpotifyAlbumHero(
                                album = album,
                                metaLine = metaLine,
                                collapseFraction = collapseFraction,
                                showPause = showPause,
                                shuffleEnabled = shuffleEnabled,
                                onPrimary = onPrimary,
                                onToggleShuffle = onToggleShuffle,
                                onFavorite = onFavorite,
                                onMore = { showMenu = true },
                                onOpenArtist = onOpenArtist
                            )
                            // Spacer matching collapsed sticky gradient length so tracks start after the fade zone
                            Spacer(modifier = Modifier.height(GradientFadeHeight - ExpandedEarlyStart))
                        }
                    }

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
                                transparentSurface = false,
                                surfaceColor = defaultBg
                            )
                        }
                    }
                }

                // Sticky vignette: stays under the header whether expanded or collapsed
                StickyAlbumGradient(
                    albumBg = albumBg,
                    defaultBg = defaultBg,
                    top = gradientTopDp,
                    height = GradientFadeHeight
                )

                CollapsedSpotifyBar(
                    album = album,
                    fraction = collapseFraction,
                    showPause = showPause,
                    barColor = albumBg,
                    onBack = onBack,
                    onPrimary = onPrimary
                )

                if (collapseFraction < 0.55f) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = scheme.onBackground
                        )
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
                        MediaSheetItem("Add to queue") {
                            onAddAlbumToQueue(album.songs)
                            Toast.makeText(
                                context,
                                "Queued ${formatTrackCount(album.songs.size)}",
                                Toast.LENGTH_SHORT
                            ).show()
                            showMenu = false
                        }
                        MediaSheetItem("Go to artist") {
                            showMenu = false
                            onOpenArtist()
                        }
                        MediaSheetItem("Add to playlist") {
                            showMenu = false
                            Toast.makeText(context, "Playlists coming soon", Toast.LENGTH_SHORT).show()
                        }
                        MediaSheetBottomPad()
                    }
                }
            }
        }
    }
}

/**
 * Long, smooth vertical fade with a soft radial vignette so the album color
 * pools under the header and dissolves into the default page background.
 * Positioned sticky under the (expanded or collapsed) header.
 */
@Composable
private fun StickyAlbumGradient(
    albumBg: Color,
    defaultBg: Color,
    top: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, with(LocalDensity.current) { top.toPx().roundToInt() }) }
            .drawBehind {
                // Multi-stop vertical for a slower dissolve
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to albumBg,
                            0.18f to albumBg.copy(alpha = 0.92f),
                            0.40f to lerpColor(albumBg, defaultBg, 0.35f),
                            0.65f to lerpColor(albumBg, defaultBg, 0.72f),
                            0.85f to defaultBg.copy(alpha = 0.92f),
                            1.00f to defaultBg
                        )
                    )
                )
                // Soft radial vignette centered near the top — edges fall off a touch earlier
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            albumBg.copy(alpha = 0.35f),
                            albumBg.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, 0f),
                        radius = size.maxDimension * 0.95f
                    )
                )
            }
    )
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * x,
        green = a.green + (b.green - a.green) * x,
        blue = a.blue + (b.blue - a.blue) * x,
        alpha = a.alpha + (b.alpha - a.alpha) * x
    )
}

@Composable
private fun SpotifyAlbumHero(
    album: AlbumItem,
    metaLine: String,
    collapseFraction: Float,
    showPause: Boolean,
    shuffleEnabled: Boolean,
    onPrimary: () -> Unit,
    onToggleShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onOpenArtist: () -> Unit
) {
    val f = collapseFraction.coerceIn(0f, 1f)
    val alpha = (1f - f * 1.2f).coerceIn(0f, 1f)
    val scale = 1f - 0.12f * f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArt(
            song = album.songs.firstOrNull(),
            size = 220.dp,
            corner = 8.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = album.displayName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )

            Spacer(modifier = Modifier.height(14.dp))

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
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (showPause) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedSpotifyBar(
    album: AlbumItem,
    fraction: Float,
    showPause: Boolean,
    barColor: Color,
    onBack: () -> Unit,
    onPrimary: () -> Unit
) {
    if (fraction <= 0.05f) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fraction }
            .background(barColor)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
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
