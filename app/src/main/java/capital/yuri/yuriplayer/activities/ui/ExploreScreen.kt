package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.ExploreSearchService
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import capital.yuri.yuriplayer.data.source.RemotePlaylist
import capital.yuri.yuriplayer.data.source.RemotePlaylistService
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.ui.LoadingEstimates
import capital.yuri.yuriplayer.ui.SongListSkeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun ExploreScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit = {},
    onOpenArtist: (ArtistItem) -> Unit = {},
    onOpenPlaylist: (Playlist) -> Unit = {}
) {
    val explore: ExploreSearchService = koinInject()
    val catalog: CatalogRepository = koinInject()
    val playlistsRepo: PlaylistRepository = koinInject()
    val remotePls: RemotePlaylistService = koinInject()
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val settings: LibrarySettings = koinInject()
    val localPlaylists by playlistsRepo.observePlaylists().collectAsState(initial = emptyList())
    val instances by sourcesRepo.observeAll().collectAsState(initial = emptyList())
    val remotes = remember(instances) { instances.filter { it.enabled } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedKeys by remember { mutableStateOf(settings.getExploreLibraryKeys()) }
    var showFilters by remember { mutableStateOf(false) }

    val scanning by explore.isScanning.collectAsState()
    val scanProgress by explore.scanProgress.collectAsState()
    val indexed by explore.indexedCount.collectAsState()
    val err by explore.lastError.collectAsState()
    val remoteSources by explore.sourceCount.collectAsState()

    var hits by remember { mutableStateOf<List<ExploreSearchService.Hit>>(emptyList()) }
    var albumHits by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var artistHits by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var playlistHits by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var remotePlaylistHits by remember { mutableStateOf<List<RemotePlaylist>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        explore.hydrateFromCatalog()
        withContext(Dispatchers.IO) { remotePls.syncOwnedToMyStuff() }
    }

    LaunchedEffect(query, selectedKeys, remotes) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = emptyList()
            albumHits = emptyList()
            artistHits = emptyList()
            playlistHits = emptyList()
            remotePlaylistHits = emptyList()
            searchBusy = false
            return@LaunchedEffect
        }
        searchBusy = true
        delay(SEARCH_DEBOUNCE_MS)
        if (query.trim() != q) return@LaunchedEffect

        val filters = exploreSourceFilters(selectedKeys, remotes)
        val songHits = withContext(Dispatchers.Default) {
            explore.searchLive(q, SONG_HIT_LIMIT, filters).take(SONG_HIT_LIMIT)
        }
        if (query.trim() != q) return@LaunchedEffect

        val albums = withContext(Dispatchers.IO) {
            val found = catalog.searchAlbumsAsItems(q, ALBUM_HIT_LIMIT)
            if (filters == null) found
            else {
                val keys = songHits.map {
                    albumKey(it.song.album, it.song.effectiveAlbumArtist ?: it.song.artist)
                }.toHashSet()
                found.filter { albumKey(it.name, it.artist) in keys }
            }
        }
        if (query.trim() != q) return@LaunchedEffect

        val artists = withContext(Dispatchers.IO) {
            val found = catalog.searchArtistsAsItems(q, ARTIST_HIT_LIMIT)
            if (filters == null) found
            else {
                val names = songHits.map { it.song.displayArtist.lowercase() }.toHashSet()
                found.filter { it.displayName.lowercase() in names }
            }
        }
        if (query.trim() != q) return@LaunchedEffect

        val includeLocal = filters == null || selectedKeys.isEmpty() || EXPLORE_LOCAL_KEY in selectedKeys
        val remoteIds = selectedKeys.mapNotNull { it.removePrefix("i:").toLongOrNull() }.toHashSet()
        val includeAllRemotes = filters == null || selectedKeys.isEmpty()

        val localIds = localPlaylists.map { it.id }.toHashSet()
        val remotePl = withContext(Dispatchers.IO) { remotePls.search(q) }
            .filter { includeAllRemotes || it.sourceInstanceId in remoteIds }
            .filter { it.ownedByUser || it.stableId !in localIds }
            .take(12)
        val remotePlIds = remotePl.map { it.stableId }.toHashSet()
        val localPl = if (includeLocal) {
            localPlaylists
                .filter { it.name.contains(q, true) && it.id !in remotePlIds }
                .take(12)
        } else {
            emptyList()
        }
        if (query.trim() != q) return@LaunchedEffect

        hits = songHits
        albumHits = albums
        artistHits = artists
        playlistHits = localPl
        remotePlaylistHits = remotePl
        searchBusy = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val allSelected = selectedKeys.isEmpty() ||
            (EXPLORE_LOCAL_KEY in selectedKeys && remotes.all { exploreInstanceKey(it.id) in selectedKeys })
        val filterSummary = when {
            allSelected -> "All libraries"
            selectedKeys.size == 1 && EXPLORE_LOCAL_KEY in selectedKeys -> "On this device"
            selectedKeys.size == 1 -> remotes.firstOrNull {
                exploreInstanceKey(it.id) in selectedKeys
            }?.name ?: "1 library"
            else -> "${selectedKeys.size} libraries"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showFilters = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Filters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                filterSummary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Icon(
                Icons.Default.FilterList,
                contentDescription = "Filter libraries",
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }

        val status = when {
            err != null && !scanning -> err!!
            scanning && scanProgress != null -> scanProgress!!
            scanning -> "Scanning remote libraries… ($indexed indexed)"
            searchBusy -> "Searching…"
            query.trim().isEmpty() -> {
                when {
                    indexed > 0 -> "Type to search · $indexed remote tracks indexed"
                    remoteSources > 0 -> "Type to search local + $remoteSources remote source(s)"
                    else -> "Type to search your library"
                }
            }
            else -> {
                val parts = buildList {
                    if (artistHits.isNotEmpty()) add("${artistHits.size} artists")
                    if (albumHits.isNotEmpty()) add("${albumHits.size} albums")
                    add("${hits.size} songs")
                }
                parts.joinToString(" · ") +
                    if (indexed > 0) " · $indexed remote indexed" else ""
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scanning || searchBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        when {
            query.trim().isNotEmpty() && searchBusy && hits.isEmpty() &&
                albumHits.isEmpty() && artistHits.isEmpty() -> {
                SongListSkeleton(LoadingEstimates.songs(null))
            }
            query.trim().isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Explore every library",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        Text(
                            "Artists, albums, songs, and playlists · local + Jellyfin + Subsonic",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            !searchBusy && hits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty() &&
                playlistHits.isEmpty() && remotePlaylistHits.isEmpty() -> {
                Text(
                    "No matches for \"${query.trim()}\".",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            else -> {
                val songs = hits.map { it.preferred.song }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (artistHits.isNotEmpty()) {
                        item { SectionHeader("Artists") }
                        items(artistHits, key = { "ar-${it.name}" }) { artist ->
                            ExploreArtistRow(
                                artist = artist,
                                onClick = { onOpenArtist(artist) }
                            )
                        }
                    }
                    if (albumHits.isNotEmpty()) {
                        item { SectionHeader("Albums") }
                        items(albumHits, key = { "al-${it.name}-${it.artist}" }) { album ->
                            ExploreAlbumRow(
                                album = album,
                                onClick = { onOpenAlbum(album) }
                            )
                        }
                    }
                    if (playlistHits.isNotEmpty() || remotePlaylistHits.isNotEmpty()) {
                        item { SectionHeader("Playlists") }
                        items(playlistHits, key = { "pl-${it.id}" }) { pl ->
                            PlaylistRow(pl, onClick = { onOpenPlaylist(pl) })
                        }
                        items(remotePlaylistHits, key = { it.stableId }) { remote ->
                            val inStuff = localPlaylists.any { it.id == remote.stableId }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val existing = localPlaylists.firstOrNull { it.id == remote.stableId }
                                            if (existing != null) {
                                                onOpenPlaylist(existing)
                                                return@launch
                                            }
                                            val created = withContext(Dispatchers.IO) {
                                                remotePls.importToLocal(remote)
                                            }
                                            if (created != null) onOpenPlaylist(created)
                                            else Toast.makeText(context, "Couldn't import playlist", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Cloud, null, Modifier.size(40.dp).padding(8.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(remote.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            inStuff -> "${remote.sourceName} · in My Stuff"
                                            remote.ownedByUser -> "${remote.sourceName} · yours"
                                            else -> "${remote.sourceName} · tap to add to My Stuff"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                    }
                    if (hits.isNotEmpty()) {
                        item { SectionHeader("Songs") }
                        itemsIndexed(hits, key = { _, h -> h.identityKey }) { index, hit ->
                            SwipeAddSongRow(
                                song = hit.preferred.song,
                                onClick = { onPlay(songs, index) },
                                onSwipeAdd = {
                                    onAddToQueue(hit.preferred.song)
                                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                },
                                showTrackNumber = false,
                                isPlaying = hit.preferred.song.isSameAs(nowPlaying),
                                isPlaybackActive = isPlaybackActive,
                                showHeart = true,
                                isExplicit = hit.isExplicit,
                                multiSource = hit.isMultiSource,
                                sourceOfferings = hit.offerings,
                                onPreferSource = { off: SourceOffering ->
                                    scope.launch {
                                        explore.setPreferredSource(hit.identityKey, off)
                                        Toast.makeText(
                                            context,
                                            "Preferred: ${off.sourceName}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ExploreFilterSheet(
            remotes = remotes,
            selectedKeys = selectedKeys,
            onDismiss = { showFilters = false },
            onChange = { next ->
                selectedKeys = next
                settings.setExploreLibraryKeys(next)
            }
        )
    }
}

private const val SEARCH_DEBOUNCE_MS = 280L
private const val SONG_HIT_LIMIT = 80
private const val ALBUM_HIT_LIMIT = 12
private const val ARTIST_HIT_LIMIT = 12
private const val EXPLORE_LOCAL_KEY = "local"

private fun exploreInstanceKey(id: Long) = "i:$id"

/** Null means every library (no filter). */
private fun exploreSourceFilters(
    selectedKeys: Set<String>,
    remotes: List<SourceInstanceEntity>
): List<Pair<String, Long?>>? {
    if (selectedKeys.isEmpty()) return null
    val allKeys = buildSet {
        add(EXPLORE_LOCAL_KEY)
        remotes.forEach { add(exploreInstanceKey(it.id)) }
    }
    if (selectedKeys.containsAll(allKeys)) return null
    return buildList {
        if (EXPLORE_LOCAL_KEY in selectedKeys) add(CatalogSources.LOCAL to null)
        remotes.forEach { row ->
            if (exploreInstanceKey(row.id) in selectedKeys) add(row.type to row.id)
        }
    }.ifEmpty { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    TopAppBar(
        title = { ExploreSearchField(query = query, onQueryChange = onQueryChange) },
        actions = actions
    )
}

@Composable
private fun ExploreSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Songs, albums, artists") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        ),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHighest,
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            disabledContainerColor = scheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreFilterSheet(
    remotes: List<SourceInstanceEntity>,
    selectedKeys: Set<String>,
    onDismiss: () -> Unit,
    onChange: (Set<String>) -> Unit
) {
    val allKeys = remember(remotes) {
        buildSet {
            add(EXPLORE_LOCAL_KEY)
            remotes.forEach { add(exploreInstanceKey(it.id)) }
        }
    }
    val effective = if (selectedKeys.isEmpty()) allKeys else selectedKeys
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onChange(emptySet()) }) {
                    Text("All")
                }
            }
            Text(
                "Libraries",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            FilterLibraryRow(
                title = "On this device",
                checked = EXPLORE_LOCAL_KEY in effective,
                onToggle = {
                    onChange(toggleExploreKey(effective, allKeys, EXPLORE_LOCAL_KEY))
                }
            )
            remotes.forEach { row ->
                val key = exploreInstanceKey(row.id)
                FilterLibraryRow(
                    title = row.name.ifBlank { row.type },
                    subtitle = row.type.lowercase().replaceFirstChar { it.titlecase() },
                    checked = key in effective,
                    onToggle = { onChange(toggleExploreKey(effective, allKeys, key)) }
                )
            }
        }
    }
}

@Composable
private fun FilterLibraryRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun toggleExploreKey(current: Set<String>, allKeys: Set<String>, key: String): Set<String> {
    val next = current.toMutableSet()
    if (key in next) next.remove(key) else next.add(key)
    if (next.isEmpty() || next.containsAll(allKeys)) return emptySet()
    return next
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun ExploreArtistRow(
    artist: ArtistItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistArt(
            artistName = artist.displayName,
            seedSong = artist.songs.firstOrNull(),
            size = 48.dp,
            circular = true
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                "${artist.trackCount} tracks · ${artist.albumCount} albums",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ExploreAlbumRow(
    album: AlbumItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(
            song = album.songs.firstOrNull(),
            size = 48.dp,
            corner = 8.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                buildString {
                    append(album.displayArtist)
                    if (album.trackCount > 0) append(" · ${album.trackCount} tracks")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

@Composable
fun SongBadgeRow(
    isExplicit: Boolean,
    multiSource: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isExplicit && !multiSource) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isExplicit) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "E",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                    lineHeight = 10.sp
                )
            }
        }
        if (multiSource) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = "Multiple sources",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}
