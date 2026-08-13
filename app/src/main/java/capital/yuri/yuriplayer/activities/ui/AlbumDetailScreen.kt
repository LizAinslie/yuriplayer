package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.theme.ThemeService
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
    isSourceActive: Boolean = false,
    isPlaying: Boolean = false,
    onBack: () -> Unit,
    onPlayAlbum: (List<Song>, Int) -> Unit,
    onTogglePlayPause: () -> Unit = {},
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

    val collapseRangePx = with(density) { 220.dp.toPx() }
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
        val song = album.songs.firstOrNull()
        themeColors = themeService.themeFromSong(context, song, base).colors
    }

    val discs = remember(album.songs) { groupByDisc(album.songs) }
    val multiDisc = discs.size > 1 || discs.keys.any { it != null && it > 1 }
    val scheme = playerColorScheme(themeColors, base)
    val tagLine = albumTagLine(album.trackCount)

    // Spotify-style primary action
    val showPause = isSourceActive && isPlaying
    val onPrimaryAction = {
        if (isSourceActive) onTogglePlayPause()
        else onPlayAlbum(album.songs, 0)
    }

    MaterialTheme(colorScheme = scheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .statusBarsPadding()
        ) {
            val headerPad = lerp(320.dp, 64.dp, collapseFraction)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = headerPad, bottom = 96.dp)
            ) {
                item { Spacer(modifier = Modifier.height(1.dp)) }

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
                            onClick = {
                                // Tapping a track always starts this album from that track
                                onPlayAlbum(album.songs, globalIndex)
                            },
                            onSwipeAdd = {
                                onAddSongToQueue(song)
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                            },
                            showTrackNumber = true
                        )
                    }
                }
            }

            MorphingAlbumHeader(
                album = album,
                tagLine = tagLine,
                fraction = collapseFraction,
                showPause = showPause,
                onBack = onBack,
                onPrimary = onPrimaryAction,
                onMore = { showMenu = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(scheme.background.copy(alpha = 0.5f + 0.5f * collapseFraction))
            )

            if (showMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showMenu = false },
                    sheetState = rememberModalBottomSheetState()
                ) {
                    Text(
                        album.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                    TextButton(
                        onClick = {
                            onAddAlbumToQueue(album.songs)
                            Toast.makeText(
                                context,
                                "Queued ${album.songs.size} tracks",
                                Toast.LENGTH_SHORT
                            ).show()
                            showMenu = false
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        Text("Add to queue", modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun MorphingAlbumHeader(
    album: AlbumItem,
    tagLine: String,
    fraction: Float,
    showPause: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val f = fraction.coerceIn(0f, 1f)
    val headerHeight = lerp(312.dp, 64.dp, f)
    val artSize = lerp(180.dp, 52.dp, f)
    val playSize = lerp(52.dp, 40.dp, f)
    val titleStyle = if (f < 0.5f) {
        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    val artistStyle = if (f < 0.5f) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.bodySmall
    }

    val primaryIcon = if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow
    val primaryDesc = if (showPause) "Pause" else "Play"

    BoxWithConstraints(
        modifier = modifier
            .height(headerHeight)
            .fillMaxWidth()
    ) {
        val width = maxWidth

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        val artStartX = 48.dp
        val artExpandedX = (width - artSize) / 2
        val artX = lerp(artExpandedX, artStartX, f)
        val artY = lerp(44.dp, 6.dp, f)
        Box(
            modifier = Modifier
                .offset(x = artX, y = artY)
                .size(artSize)
        ) {
            AlbumArt(
                song = album.songs.firstOrNull(),
                size = artSize,
                corner = lerp(12.dp, 6.dp, f)
            )
        }

        val metaTop = lerp(44.dp + 180.dp + 16.dp, 6.dp, f)
        val metaStart = lerp(16.dp, 48.dp + 52.dp + 10.dp, f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = metaStart, end = 4.dp)
                .offset(y = metaTop),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (f < 0.55f) {
                IconButton(
                    onClick = onPrimary,
                    modifier = Modifier
                        .size(playSize)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        primaryIcon,
                        contentDescription = primaryDesc,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(playSize * 0.55f)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(
                    text = tagLine,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = lerp(11.sp, 10.sp, f)
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
                MarqueeText(
                    text = album.displayName,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onBackground
                )
                MarqueeText(
                    text = album.displayArtist,
                    style = artistStyle,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                )
            }

            if (f >= 0.55f) {
                IconButton(
                    onClick = onPrimary,
                    modifier = Modifier
                        .size(playSize)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        primaryIcon,
                        contentDescription = primaryDesc,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(playSize * 0.55f)
                    )
                }
            }
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (f < 0.85f) {
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f * (1f - f))
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
    trackCount <= 3 -> "SINGLE"
    trackCount <= 8 -> "EP"
    else -> "LP"
}

private fun albumTagLine(trackCount: Int): String =
    "${guessReleaseType(trackCount)} · ${trackCount}tr"

private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp =
    androidx.compose.ui.unit.lerp(start, stop, fraction.coerceIn(0f, 1f))

private fun lerp(start: androidx.compose.ui.unit.TextUnit, stop: androidx.compose.ui.unit.TextUnit, fraction: Float) =
    androidx.compose.ui.unit.lerp(start, stop, fraction.coerceIn(0f, 1f))
