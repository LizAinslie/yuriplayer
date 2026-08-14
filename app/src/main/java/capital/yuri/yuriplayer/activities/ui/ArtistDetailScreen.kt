package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.ReleaseType
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.releaseType
import capital.yuri.yuriplayer.data.releaseYear
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.formatAlbumCount
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject

private enum class ArtistReleaseFilter { All, Albums, EPs, Singles }

@Composable
fun ArtistDetailScreen(
    artist: ArtistItem,
    albums: List<AlbumItem>,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    val themeService: ThemeService = koinInject()
    val profileRepo: ArtistProfileRepository = koinInject()
    val base = MaterialTheme.colorScheme
    val context = LocalContext.current
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }
    var filter by remember { mutableStateOf(ArtistReleaseFilter.All) }
    val uriHandler = LocalUriHandler.current

    val profile by profileRepo.observe(artist.displayName).collectAsState(initial = null)

    LaunchedEffect(artist.name, albums.size) {
        profileRepo.resolve(artist.displayName)
        val seed = albums.firstOrNull()?.songs?.firstOrNull() ?: artist.songs.firstOrNull()
        themeColors = themeService.themeFromSong(context, seed, base).colors
    }

    val scheme = playerColorScheme(themeColors, base)
    val artistBg = scheme.background

    val sortedAlbums = remember(albums) {
        albums.sortedWith(
            compareByDescending<AlbumItem> { it.releaseYear() ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }
    val filtered = remember(sortedAlbums, filter) {
        when (filter) {
            ArtistReleaseFilter.All -> sortedAlbums
            ArtistReleaseFilter.Albums -> sortedAlbums.filter { it.releaseType() == ReleaseType.ALBUM }
            ArtistReleaseFilter.EPs -> sortedAlbums.filter { it.releaseType() == ReleaseType.EP }
            ArtistReleaseFilter.Singles -> sortedAlbums.filter { it.releaseType() == ReleaseType.SINGLE }
        }
    }

    ThemedStatusBar(color = artistBg, enabled = true)

    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(base.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(artistBg, artistBg.copy(alpha = 0.5f), Color.Transparent)
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = scheme.onBackground
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item {
                        ArtistHero(
                            name = artist.displayName,
                            seedSong = albums.firstOrNull()?.songs?.firstOrNull()
                                ?: artist.songs.firstOrNull(),
                            stats = "${formatAlbumCount(artist.albumCount)} · ${formatTrackCount(artist.trackCount)}",
                            bio = profile?.bio,
                            website = profile?.websiteUrl,
                            links = profile?.links.orEmpty(),
                            onPlayAll = {
                                if (artist.songs.isNotEmpty()) onPlaySongs(artist.songs, 0)
                            },
                            onOpenLink = { url -> runCatching { uriHandler.openUri(url) } }
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ArtistReleaseFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text(f.name) }
                                )
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No releases in this filter.",
                                modifier = Modifier.padding(16.dp),
                                color = scheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        items(
                            filtered,
                            key = { "${it.name}|${it.artist}|${it.releaseYear()}" }
                        ) { album ->
                            ArtistReleaseRow(album) { onOpenAlbum(album) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHero(
    name: String,
    seedSong: Song?,
    stats: String,
    bio: String?,
    website: String?,
    links: List<ArtistLink>,
    onPlayAll: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AlbumArt(song = seedSong, size = 160.dp, corner = 80.dp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        MarqueeText(
            text = name,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            stats,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlayAll,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play all",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            if (!website.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { onOpenLink(website) }) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = "Website",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                }
            }
            links.take(3).forEach { link ->
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    link.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenLink(link.url) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        if (!bio.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ArtistReleaseRow(album: AlbumItem, onClick: () -> Unit) {
    val year = album.releaseYear()
    val type = album.releaseType()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 56.dp, corner = 4.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = album.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                buildString {
                    append(type.label)
                    if (year != null) append(" · $year")
                    append(" · ${formatTrackCount(album.trackCount)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
