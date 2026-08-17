package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.ReleaseType
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.releaseType
import capital.yuri.yuriplayer.data.releaseYear
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.formatAlbumCount
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

private val ArtistHeaderHeight = 300.dp
private val ArtistGradientFade = 200.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artist: ArtistItem,
    albums: List<AlbumItem>,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onStartRadio: () -> Unit = {},
    onAddToQueue: (Song) -> Unit = {}
) {
    val themeService: ThemeService = koinInject()
    val profileRepo: ArtistProfileRepository = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    val entries by pinStore.entries.collectAsState()
    val base = MaterialTheme.colorScheme
    val context = LocalContext.current
    val density = LocalDensity.current
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }
    var filter by remember { mutableStateOf(ArtistReleaseFilter.All) }
    var showAll by remember { mutableStateOf(false) }
    var discographyFilters by remember { mutableStateOf(DiscographyFilters()) }
    var showDataSources by remember { mutableStateOf(false) }
    var dataLinks by remember { mutableStateOf<List<ArtistLink>>(emptyList()) }
    val uriHandler = LocalUriHandler.current

    val profile by profileRepo.observe(artist.displayName).collectAsState(initial = null)

    val artistKeyStr = artistKey(artist.name) ?: artist.displayName.lowercase()
    val artistSaved = remember(entries, artistKeyStr) {
        pinStore.contains(StuffPinKind.ARTIST, artistKeyStr)
    }

    // Await resolve so we theme from the *fresh* profile image when MB has one,
    // otherwise extract from the first release cover (same path as album pages).
    LaunchedEffect(artist.name, albums.size) {
        val resolved = runCatching { profileRepo.resolve(artist.displayName) }.getOrNull()
        val profileUri = resolved?.imageUri?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching {
                    when {
                        raw.startsWith("/") -> Uri.fromFile(java.io.File(raw))
                        else -> Uri.parse(raw)
                    }
                }.getOrNull()
            }

        dataLinks = buildList {
            resolved?.websiteUrl?.takeIf { it.isNotBlank() }?.let {
                add(ArtistLink("Website", it))
            }
            addAll(resolved?.links.orEmpty())
            // Always offer MusicBrainz search as a data source
            val q = java.net.URLEncoder.encode(artist.displayName, "UTF-8")
            add(ArtistLink("MusicBrainz", "https://musicbrainz.org/search?query=$q&type=artist"))
        }.distinctBy { it.url }

        themeColors = if (profileUri != null) {
            themeService.themeFromUri(
                context = context,
                key = "artist:${artistKeyStr}:${resolved?.imageUri}",
                uri = profileUri,
                base = base
            ).colors
        } else {
            val seed = albums.firstOrNull()?.songs?.firstOrNull()
                ?: artist.songs.firstOrNull()
            themeService.themeFromSong(
                context = context,
                song = seed,
                base = base,
                forceRefresh = true
            ).colors
        }
    }

    val artBg = themeColors.container
    val defaultBg = base.background
    val onArt = themeColors.onContainer
    val mutedOnArt = onArt.copy(alpha = 0.6f)
    val accent = themeColors.accent
    val onAccent = themeColors.onAccent

    val fadePx = with(density) { ArtistGradientFade.toPx() }
    val headerPx = with(density) { ArtistHeaderHeight.toPx() }

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

    ThemedStatusBar(color = artBg, enabled = true)

    if (showDataSources) {
        ModalBottomSheet(
            onDismissRequest = { showDataSources = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                "Data sources",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Text(
                "Links from MusicBrainz and related catalogs for ${artist.displayName}. " +
                    "Not every artist has external links in the database.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, bottom = 12.dp)
            )
            if (dataLinks.isEmpty()) {
                Text(
                    "No external links found yet.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                dataLinks.forEach { link ->
                    Text(
                        link.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { uriHandler.openUri(link.url) }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showAll) {
        DiscographyAllScreen(
            artistName = artist.displayName,
            albums = sortedAlbums,
            filters = discographyFilters,
            onFiltersChange = { discographyFilters = it },
            titleColor = base.onBackground,
            mutedColor = base.onBackground.copy(alpha = 0.6f),
            onBack = { showAll = false },
            onOpenAlbum = onOpenAlbum,
            onPlaySongs = onPlaySongs,
            onAddToQueue = onAddToQueue
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(defaultBg)
            .drawBehind {
                val solidEnd = headerPx
                val fadeEnd = solidEnd + fadePx
                drawRect(
                    color = artBg,
                    topLeft = Offset.Zero,
                    size = Size(size.width, solidEnd.coerceAtMost(size.height))
                )
                if (fadeEnd > solidEnd) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                artBg,
                                artBg.copy(alpha = 0.75f),
                                artBg.copy(alpha = 0.35f),
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
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onArt
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showDataSources = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = onArt
                    )
                }
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
                        titleColor = onArt,
                        mutedColor = mutedOnArt,
                        accent = accent,
                        onAccent = onAccent,
                        artistSaved = artistSaved,
                        onToggleFavorite = {
                            val now = pinStore.toggleArtist(artist)
                            Toast.makeText(
                                context,
                                if (now) "Added to My Stuff" else "Removed from My Stuff",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onPlayAll = onStartRadio
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
                            color = base.onBackground,
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
                                Text("Show all", color = accent)
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
                                        color = if (filter == f) base.onSecondaryContainer
                                        else base.onBackground
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
                            color = base.onBackground.copy(alpha = 0.55f)
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
                                    titleColor = base.onBackground,
                                    mutedColor = base.onBackground.copy(alpha = 0.55f),
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
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    val context = LocalContext.current
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
            androidx.compose.material3.Surface(
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
                    SwipeAddSongRow(
                        song = song,
                        onClick = { onPlaySongs(tracks, index) },
                        onSwipeAdd = {
                            onAddToQueue(song)
                            Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                        },
                        showTrackNumber = true,
                        transparentSurface = true,
                        showHeart = true
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
private fun ArtistHero(
    name: String,
    seedSong: Song?,
    stats: String,
    bio: String?,
    titleColor: Color,
    mutedColor: Color,
    accent: Color,
    onAccent: Color,
    artistSaved: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtistArt(
            artistName = name,
            seedSong = seedSong,
            size = 160.dp,
            circular = true
        )

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
                    .background(accent)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Start radio",
                    tint = onAccent
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            MyStuffHeart(
                saved = artistSaved,
                onToggle = onToggleFavorite,
                tint = titleColor.copy(alpha = 0.85f),
                savedTint = accent
            )
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
