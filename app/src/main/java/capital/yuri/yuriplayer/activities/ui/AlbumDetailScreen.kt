package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
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

    val releaseYear = remember(album.songs) {
        album.songs.mapNotNull { it.year }.maxOrNull()
    }
    val releaseType = guessReleaseType(album.trackCount)
    val metaLine = buildString {
        append(releaseType)
        if (releaseYear != null) append(" · $releaseYear")
        append(" · ${album.trackCount} tracks")
    }

    val showPause = isSourceActive && isPlaying
    val onPrimary = {
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
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
                            showTrackNumber = true
                        )
                    }
                }
            }

            // Thin collapsed bar
            CollapsedSpotifyBar(
                album = album,
                fraction = collapseFraction,
                showPause = showPause,
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

/**
 * Expanded layout modeled on Spotify:
 * centered art → title → artist chip → type · year · tracks →
 * heart · ··· · shuffle · big play
 */
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

        // Title — left aligned block under art (Spotify stacks left on phone)
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

            // Artist row with small cover circle
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

            // Actions: ♡  ···     shuffle   [play]
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
    onBack: () -> Unit,
    onPrimary: () -> Unit
) {
    if (fraction <= 0.05f) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fraction }
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
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

/** Spotify-style label: Album / EP / Single (we still expose LP as Album). */
private fun guessReleaseType(trackCount: Int): String = when {
    trackCount <= 3 -> "Single"
    trackCount <= 8 -> "EP"
    else -> "Album"
}
