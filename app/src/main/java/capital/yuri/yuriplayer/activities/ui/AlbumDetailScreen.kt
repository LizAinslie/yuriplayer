package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.theme.ThemeService
import org.koin.compose.koinInject
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
    onBack: () -> Unit,
    onPlayAlbum: (List<Song>, Int) -> Unit,
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

    val collapseRangePx = with(density) { 160.dp.toPx() }
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

    MaterialTheme(colorScheme = scheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .statusBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Tall header
                item {
                    AlbumHeroHeader(
                        album = album,
                        collapseFraction = collapseFraction,
                        onPlay = { onPlayAlbum(album.songs, 0) },
                        onMore = { showMenu = true }
                    )
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
                    ) { indexInDisc, song ->
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
                            showTrackNumber = true
                        )
                    }
                }
            }

            // Collapsed thin bar (fades in)
            CollapsedAlbumBar(
                album = album,
                fraction = collapseFraction,
                onBack = onBack,
                onPlay = { onPlayAlbum(album.songs, 0) },
                onMore = { showMenu = true }
            )

            // Back always visible when not collapsed enough
            if (collapseFraction < 0.5f) {
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
                    Spacer(Modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun AlbumHeroHeader(
    album: AlbumItem,
    collapseFraction: Float,
    onPlay: () -> Unit,
    onMore: () -> Unit
) {
    val scale = 1f - 0.15f * collapseFraction
    val alpha = 1f - collapseFraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        AlbumArt(
            song = album.songs.firstOrNull(),
            size = 200.dp,
            corner = 12.dp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = buildString {
                append(guessReleaseType(album.trackCount))
                // genre tags later
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        MarqueeText(
            text = album.displayName,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        MarqueeText(
            text = album.displayArtist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${album.trackCount} tracks",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
    }
}

@Composable
private fun CollapsedAlbumBar(
    album: AlbumItem,
    fraction: Float,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit
) {
    if (fraction <= 0.01f) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fraction }
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
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
            Text(
                text = guessReleaseType(album.trackCount),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                maxLines = 1
            )
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
        IconButton(
            onClick = onPlay,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onBackground
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
    // Preserve disc order: nulls last as disc 1-equivalent when alone
    return grouped.toSortedMap(compareBy { it ?: 1 })
}

private fun guessReleaseType(trackCount: Int): String = when {
    trackCount <= 3 -> "Single"
    trackCount <= 8 -> "EP"
    else -> "LP"
}
