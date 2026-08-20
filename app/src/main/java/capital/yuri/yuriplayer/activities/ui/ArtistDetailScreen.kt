package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.ReleaseType
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.releaseType
import capital.yuri.yuriplayer.data.releaseYear
import capital.yuri.yuriplayer.data.source.ArtistEvent
import capital.yuri.yuriplayer.data.source.ArtistImageKind
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.ui.formatAlbumCount
import capital.yuri.yuriplayer.ui.formatTrackCount
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
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

/** Stable, unique Lazy key for a release card / section. */
private fun releaseLazyKey(album: AlbumItem, index: Int): String {
    val base = albumKey(album.name, album.artist)
    val seed = album.songs.firstOrNull()?.songKey.orEmpty()
    return "$base|$index|$seed"
}

private val ArtistBannerBodyHeight = 200.dp
private val ArtistAvatarOnBanner = 132.dp
private val ArtistAvatarPlain = 160.dp
private val ArtistGradientFade = 180.dp
private val ArtistHeaderHeightPlain = 280.dp

private fun parseImageUri(raw: String?): Uri? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        when {
            raw.startsWith("/") -> Uri.fromFile(java.io.File(raw))
            else -> Uri.parse(raw)
        }
    }.getOrNull()
}

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
    val artistInfo: ArtistInfoService = koinInject()
    val pinStore: MyStuffPinStore = koinInject()
    val entries by pinStore.entries.collectAsState()
    val base = MaterialTheme.colorScheme
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var themeColors by remember { mutableStateOf(fallbackPlayerColors(base)) }
    var filter by remember { mutableStateOf(ArtistReleaseFilter.All) }
    var showAll by remember { mutableStateOf(false) }
    var discographyFilters by remember { mutableStateOf(DiscographyFilters()) }
    var showMenu by remember { mutableStateOf(false) }
    var showDataSources by remember { mutableStateOf(false) }
    var fetchKind by remember { mutableStateOf<ArtistImageKind?>(null) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var cropKind by remember { mutableStateOf(ArtistImageKind.PROFILE) }
    var dataLinks by remember { mutableStateOf<List<ArtistLink>>(emptyList()) }
    var events by remember { mutableStateOf<List<ArtistEvent>>(emptyList()) }
    var themeTick by remember { mutableStateOf(0) }
    var bannerUri by remember { mutableStateOf(profileRepo.bannerUri(artist.displayName)) }
    val uriHandler = LocalUriHandler.current

    val profile by profileRepo.observe(artist.displayName).collectAsState(initial = null)

    val artistKeyStr = artistKey(artist.name) ?: artist.displayName.lowercase()
    val artistSaved = remember(entries, artistKeyStr) {
        pinStore.contains(StuffPinKind.ARTIST, artistKeyStr)
    }

    val pickProfile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cropKind = ArtistImageKind.PROFILE
            cropUri = uri
        }
    }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cropKind = ArtistImageKind.BANNER
            cropUri = uri
        }
    }

    LaunchedEffect(artist.name, albums.size, profile?.imageUri, profile?.bio, profile?.genres, themeTick) {
        val resolved = runCatching { profileRepo.resolve(artist.displayName) }.getOrNull()
        val diskBanner = profileRepo.bannerUri(artist.displayName)
        if (diskBanner != bannerUri) bannerUri = diskBanner
        val themeUri = parseImageUri(bannerUri)
            ?: parseImageUri(profile?.imageUri ?: resolved?.imageUri)

        dataLinks = (resolved?.links.orEmpty() + profile?.links.orEmpty())
            .distinctBy { it.url.lowercase() }

        events = runCatching { artistInfo.upcomingEvents(artist.displayName) }
            .getOrDefault(emptyList())

        themeColors = if (themeUri != null) {
            themeService.themeFromUri(
                context = context,
                key = "artist:${artistKeyStr}:${themeUri}",
                uri = themeUri,
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

    val hasBanner = !bannerUri.isNullOrBlank()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bannerTotalHeight: Dp = if (hasBanner) statusBarTop + ArtistBannerBodyHeight else 0.dp

    val solidHeaderHeight = if (hasBanner) statusBarTop + ArtistBannerBodyHeight else ArtistHeaderHeightPlain
    val fadePx = with(density) { ArtistGradientFade.toPx() }
    val headerPx = with(density) { solidHeaderHeight.toPx() }

    // Dedupe again at UI boundary so Lazy keys cannot collide
    val sortedAlbums = remember(albums) {
        albums
            .groupBy { albumKey(it.name, it.artist) }
            .map { (_, group) -> group.maxByOrNull { it.trackCount } ?: group.first() }
            .sortedWith(
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

    val rootArtistNav = LocalArtistNav.current
    val nestedArtistNav = rootArtistNav.copy(
        startRadio = { onStartRadio() },
        changeImage = { pickProfile.launch("image/*") },
        fetchImage = { fetchKind = ArtistImageKind.PROFILE },
        changeBanner = { pickBanner.launch("image/*") },
        fetchBanner = { fetchKind = ArtistImageKind.BANNER },
        clearImage = {
            scope.launch {
                profileRepo.setCustomImage(artist.displayName, null)
                themeTick++
                Toast.makeText(context, "Artist image cleared", Toast.LENGTH_SHORT).show()
            }
        },
        clearBanner = {
            scope.launch {
                profileRepo.setBannerImage(artist.displayName, null)
                bannerUri = null
                themeTick++
                Toast.makeText(context, "Banner cleared", Toast.LENGTH_SHORT).show()
            }
        },
        openLinks = { showDataSources = true }
    )

    CompositionLocalProvider(LocalArtistNav provides nestedArtistNav) {
        if (cropUri != null) {
            ImageCropScreen(
                sourceUri = cropUri!!,
                title = if (cropKind == ArtistImageKind.BANNER) "Crop banner" else "Crop artist image",
                aspect = if (cropKind == ArtistImageKind.BANNER) 16f / 9f else 1f,
                onCancel = { cropUri = null },
                onCropped = { cropped ->
                    cropUri = null
                    scope.launch {
                        when (cropKind) {
                            ArtistImageKind.PROFILE -> {
                                profileRepo.setCustomImage(artist.displayName, cropped.toString())
                                Toast.makeText(context, "Artist image updated", Toast.LENGTH_SHORT).show()
                            }
                            ArtistImageKind.BANNER -> {
                                val saved = profileRepo.setBannerImage(
                                    artist.displayName,
                                    cropped.toString()
                                )
                                bannerUri = saved
                                Toast.makeText(context, "Banner updated", Toast.LENGTH_SHORT).show()
                            }
                        }
                        themeTick++
                    }
                }
            )
            return@CompositionLocalProvider
        }

        ThemedStatusBar(
            color = if (hasBanner) Color.Transparent else artBg,
            enabled = true
        )

        if (fetchKind != null) {
            FetchArtistImageSheet(
                artistName = artist.displayName,
                kind = fetchKind!!,
                onDismiss = { fetchKind = null },
                onPicked = { uri ->
                    cropKind = fetchKind!!
                    fetchKind = null
                    cropUri = uri
                }
            )
        }

        if (showMenu) {
            ArtistContextSheet(
                artist = artist,
                onDismiss = { showMenu = false }
            )
        }

        if (showDataSources) {
            ModalBottomSheet(
                onDismissRequest = { showDataSources = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Text(
                    "Links",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Text(
                    "Official, streaming, social, and catalog links for ${artist.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp)
                )
                ArtistDataSourcesContent(
                    links = dataLinks.ifEmpty { profile?.links.orEmpty() },
                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
                )
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
            return@CompositionLocalProvider
        }

        val hasLinks = dataLinks.isNotEmpty() || !profile?.links.isNullOrEmpty()

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(defaultBg)
                .drawBehind {
                    if (!hasBanner) {
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
                }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
                    ArtistHero(
                        name = artist.displayName,
                        seedSong = albums.firstOrNull()?.songs?.firstOrNull()
                            ?: artist.songs.firstOrNull(),
                        bannerUri = bannerUri,
                        bannerTotalHeight = bannerTotalHeight,
                        artBg = artBg,
                        stats = "${formatAlbumCount(artist.albumCount)} · ${formatTrackCount(artist.trackCount)}",
                        titleColor = onArt,
                        mutedColor = mutedOnArt,
                        accent = accent,
                        onAccent = onAccent,
                        artistSaved = artistSaved,
                        hasLinks = hasLinks,
                        onToggleFavorite = {
                            val now = pinStore.toggleArtist(artist)
                            Toast.makeText(
                                context,
                                if (now) "Added to My Stuff" else "Removed from My Stuff",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenLinks = { showDataSources = true },
                        onPlayAll = onStartRadio
                    )
                }

                item {
                    ArtistGenreChips(
                        genres = profile?.genres.orEmpty(),
                        titleColor = onArt,
                        mutedColor = mutedOnArt
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
                            itemsIndexed(
                                filtered,
                                key = { i, a -> releaseLazyKey(a, i) }
                            ) { _, album ->
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

                item {
                    ArtistUpcomingShows(
                        events = events,
                        titleColor = base.onBackground,
                        mutedColor = base.onBackground.copy(alpha = 0.6f),
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
                    )
                }

                item {
                    val bio = profile?.bio
                    if (!bio.isNullOrBlank()) {
                        ArtistBioCard(
                            bio = bio,
                            titleColor = base.onBackground,
                            mutedColor = base.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (hasBanner) 0.28f else 0f))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onArt
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (hasBanner) 0.28f else 0f))
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = onArt
                    )
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
        albums
            .filter { filters.matches(it.releaseType()) }
            .groupBy { albumKey(it.name, it.artist) }
            .map { (_, group) -> group.maxByOrNull { it.trackCount } ?: group.first() }
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
            visible.forEachIndexed { index, album ->
                val releaseKey = releaseLazyKey(album, index)
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
                ) { trackIndex, song ->
                    SwipeAddSongRow(
                        song = song,
                        onClick = { onPlaySongs(tracks, trackIndex) },
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
    bannerUri: String?,
    bannerTotalHeight: Dp,
    artBg: Color,
    stats: String,
    titleColor: Color,
    mutedColor: Color,
    accent: Color,
    onAccent: Color,
    artistSaved: Boolean,
    hasLinks: Boolean = false,
    onToggleFavorite: () -> Unit,
    onOpenLinks: () -> Unit = {},
    onPlayAll: () -> Unit
) {
    val context = LocalContext.current
    val hasBanner = !bannerUri.isNullOrBlank()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasBanner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerTotalHeight + ArtistAvatarOnBanner / 2)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bannerTotalHeight)
                        .align(Alignment.TopCenter)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(bannerUri)
                            .memoryCacheKey(bannerUri)
                            .diskCacheKey(bannerUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "$name banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        artBg.copy(alpha = 0.55f),
                                        artBg.copy(alpha = 0.9f)
                                    )
                                )
                            )
                    )
                }

                Box(
                    modifier = Modifier
                        .size(ArtistAvatarOnBanner)
                        .align(Alignment.BottomCenter)
                        .border(3.dp, titleColor.copy(alpha = 0.9f), CircleShape)
                        .clip(CircleShape)
                ) {
                    ArtistArt(
                        artistName = name,
                        seedSong = seedSong,
                        size = ArtistAvatarOnBanner,
                        circular = true
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(48.dp))
            ArtistArt(
                artistName = name,
                seedSong = seedSong,
                size = ArtistAvatarPlain,
                circular = true
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                if (hasLinks) {
                    IconButton(onClick = onOpenLinks) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Links",
                            tint = titleColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }
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
