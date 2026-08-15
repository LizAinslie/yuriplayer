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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import capital.yuri.yuriplayer.ui.formatDuration
import capital.yuri.yuriplayer.ui.formatTrackCount
import org.koin.compose.koinInject

private enum class ArtistReleaseFilter { All, Albums, EPs, Singles }

private data class DiscographyFilters(
    val albums: Boolean = true,
    val eps: Boolean = true,
    val singles: Boolean = true
) {
    fun matches(type: ReleaseType): Boolean = when (type) {
        ReleaseType.ALBUM, ReleaseType.COMPILATION, ReleaseType.OTHER -> albums
        ReleaseType.EP -> eps
        ReleaseType.SINGLE -> singles
    }

    fun label(): String {
        val parts = buildList {
            if (albums) add("LPs")
            if (eps) add("EPs")
            if (singles) add("Singles")
        }
        return when {
            parts.isEmpty() -> "None"
            parts.size == 3 -> "All types"
            else -> parts.joinToString(", ")
        }
    }

    companion object {
        fun fromPageFilter(filter: ArtistReleaseFilter): DiscographyFilters = when (filter) {
            ArtistReleaseFilter.All -> DiscographyFilters()
            ArtistReleaseFilter.Albums -> DiscographyFilters(albums = true, eps = false, singles = false)
            ArtistReleaseFilter.EPs -> DiscographyFilters(albums = false, eps = true, singles = false)
            ArtistReleaseFilter.Singles -> DiscographyFilters(albums = false, eps = false, singles = true)
        }
    }
}

private fun sortedReleaseTracks(album: AlbumItem): List<Song> =
    album.songs.sortedWith(
        compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
    )

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
    var showAll by remember { mutableStateOf(false) }
    var discographyFilters by remember { mutableStateOf(DiscographyFilters()) }
    val uriHandler = LocalUriHandler.current

    val profile by profileRepo.observe(artist.displayName).collectAsState(initial = null)

    LaunchedEffect(artist.name, albums.size) {
        profileRepo.resolve(artist.displayName)
        val seed = albums.firstOrNull()?.songs?.firstOrNull() ?: artist.songs.firstOrNull()
        themeColors = themeService.themeFromSong(context, seed, base).colors
    }

    val scheme = playerColorScheme(themeColors, base)
    val artistBg = scheme.background
    val onBg = scheme.onBackground
    val muted = onBg.copy(alpha = 0.6f)

    val sortedAlbums = remember(albums) {
        albums.sortedWith(
            compareByDescending<AlbumItem> { it.releaseYear() ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }
    val filtered = remember(sortedAlbums, filter) {
        when (filter) {
            ArtistReleaseFilter.All -> sortedAlbums
            ArtistReleaseFilter.Albums -> sortedAlbums.filter {
                it.releaseType() == ReleaseType.ALBUM || it.releaseType() == ReleaseType.COMPILATION
            }
            ArtistReleaseFilter.EPs -> sortedAlbums.filter { it.releaseType() == ReleaseType.EP }
            ArtistReleaseFilter.Singles -> sortedAlbums.filter { it.releaseType() == ReleaseType.SINGLE }
        }
    }

    ThemedStatusBar(color = artistBg, enabled = true)

    MaterialTheme(colorScheme = scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = onBg
        ) {
            if (showAll) {
                DiscographyAllScreen(
                    artistName = artist.displayName,
                    albums = sortedAlbums,
                    filters = discographyFilters,
                    onFiltersChange = { discographyFilters = it },
                    titleColor = onBg,
                    mutedColor = muted,
                    onBack = { showAll = false },
                    onOpenAlbum = onOpenAlbum,
                    onPlaySongs = onPlaySongs
                )
                return@Surface
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(artistBg, artistBg.copy(alpha = 0.55f), Color.Transparent)
                            )
                        )
                )

                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onBg
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
                                titleColor = onBg,
                                mutedColor = muted,
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
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Discography",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = onBg,
                                    modifier = Modifier.weight(1f)
                                )
                                if (sortedAlbums.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            discographyFilters =
                                                DiscographyFilters.fromPageFilter(filter)
                                            showAll = true
                                        }
                                    ) {
                                        Text("Show all", color = scheme.primary)
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ArtistReleaseFilter.entries.forEach { f ->
                                    FilterChip(
                                        selected = filter == f,
                                        onClick = { filter = f },
                                        label = {
                                            Text(
                                                f.name,
                                                color = if (filter == f) scheme.onSecondaryContainer
                                                else onBg
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        item {
                            if (filtered.isEmpty()) {
                                Text(
                                    "No releases in this filter.",
                                    modifier = Modifier.padding(16.dp),
                                    color = muted
                                )
                            } else {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(
                                        filtered,
                                        key = { "${it.name}|${it.artist}|${it.releaseYear()}" }
                                    ) { album ->
                                        ArtistReleaseCard(
                                            album = album,
                                            titleColor = onBg,
                                            mutedColor = muted,
                                            onClick = { onOpenAlbum(album) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscographyAllScreen(
    artistName: String,
    albums: List<AlbumItem>,
    filters: DiscographyFilters,
    onFiltersChange: (DiscographyFilters) -> Unit,
    titleColor: Color,
    mutedColor: Color,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    var filterMenuOpen by remember { mutableStateOf(false) }
    val visible = remember(albums, filters) {
        albums.filter { filters.matches(it.releaseType()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = titleColor
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Discography",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Text(
                    artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { filterMenuOpen = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filter: ${filters.label()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = titleColor,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Filter types",
                        tint = mutedColor
                    )
                }
            }
            DropdownMenu(
                expanded = filterMenuOpen,
                onDismissRequest = { filterMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = filters.albums, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("LPs")
                        }
                    },
                    onClick = { onFiltersChange(filters.copy(albums = !filters.albums)) }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = filters.eps, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("EPs")
                        }
                    },
                    onClick = { onFiltersChange(filters.copy(eps = !filters.eps)) }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = filters.singles, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Singles")
                        }
                    },
                    onClick = { onFiltersChange(filters.copy(singles = !filters.singles)) }
                )
            }
        }

        Text(
            if (visible.isEmpty()) "No releases match these filters."
            else if (visible.size == 1) "1 release"
            else "${visible.size} releases",
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Spotify-style: release header, then that release's tracks (LPs / EPs / singles).
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            visible.forEach { album ->
                val releaseKey = "${album.name}|${album.artist}|${album.releaseYear()}"
                val tracks = sortedReleaseTracks(album)

                item(key = "hdr-$releaseKey") {
                    DiscographyReleaseHeader(
                        album = album,
                        titleColor = titleColor,
                        mutedColor = mutedColor,
                        onClick = { onOpenAlbum(album) }
                    )
                }

                itemsIndexed(
                    tracks,
                    key = { _, song -> "trk-$releaseKey-${song.songKey}" }
                ) { index, song ->
                    DiscographyTrackRow(
                        song = song,
                        indexInRelease = index,
                        titleColor = titleColor,
                        mutedColor = mutedColor,
                        onClick = { onPlaySongs(tracks, index) }
                    )
                }

                item(key = "sp-$releaseKey") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DiscographyReleaseHeader(
    album: AlbumItem,
    titleColor: Color,
    mutedColor: Color,
    onClick: () -> Unit
) {
    val year = album.releaseYear()
    val type = album.releaseType()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 64.dp, corner = 4.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = album.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = titleColor
            )
            Text(
                buildString {
                    append(type.label)
                    if (year != null) append(" · $year")
                    append(" · ${formatTrackCount(album.trackCount)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor
            )
        }
    }
}

@Composable
private fun DiscographyTrackRow(
    song: Song,
    indexInRelease: Int,
    titleColor: Color,
    mutedColor: Color,
    onClick: () -> Unit
) {
    val trackLabel = song.trackNumber?.toString()
        ?: (indexInRelease + 1).toString()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = trackLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = song.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            val albumArtist = song.effectiveAlbumArtist
            val trackArtist = song.artist
            if (!trackArtist.isNullOrBlank() &&
                !trackArtist.equals(albumArtist, ignoreCase = true)
            ) {
                MarqueeText(
                    text = trackArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor
                )
            }
        }
        song.durationMs?.takeIf { it > 0 }?.let { ms ->
            Text(
                formatDuration(ms),
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                modifier = Modifier.padding(start = 8.dp)
            )
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
    titleColor: Color,
    mutedColor: Color,
    onPlayAll: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
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
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AlbumArt(song = seedSong, size = 160.dp, corner = 80.dp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        MarqueeText(
            text = name,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = titleColor,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            stats,
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColor,
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
                    .background(scheme.primary)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play all",
                    tint = scheme.onPrimary
                )
            }
            if (!website.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { onOpenLink(website) }) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = "Website",
                        tint = titleColor.copy(alpha = 0.85f)
                    )
                }
            }
            links.take(3).forEach { link ->
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    link.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
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
                color = titleColor.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ArtistReleaseCard(
    album: AlbumItem,
    titleColor: Color,
    mutedColor: Color,
    onClick: () -> Unit
) {
    val year = album.releaseYear()
    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArt(
            song = album.songs.firstOrNull(),
            size = 148.dp,
            corner = 6.dp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.displayName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = year?.toString() ?: album.releaseType().label,
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
