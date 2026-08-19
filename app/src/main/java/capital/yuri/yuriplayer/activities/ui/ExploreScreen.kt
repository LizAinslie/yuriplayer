package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.ExploreSearchService
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.source.SourceOffering
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ExploreScreen(
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit = {},
    onOpenArtist: (ArtistItem) -> Unit = {},
    forceRescanKey: Int = 0
) {
    val explore: ExploreSearchService = koinInject()
    val library: LibraryIndex = koinInject()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var sourcesFor by remember { mutableStateOf<ExploreSearchService.Hit?>(null) }

    val scanning by explore.isScanning.collectAsState()
    val scanProgress by explore.scanProgress.collectAsState()
    val indexed by explore.indexedCount.collectAsState()
    val remoteOfferings by explore.remoteOfferings.collectAsState()
    val err by explore.lastError.collectAsState()
    val remoteSources by explore.sourceCount.collectAsState()
    val allSongs by library.songs.collectAsState()

    LaunchedEffect(Unit) {
        explore.hydrateFromCatalog()
        explore.requestRemoteScan(force = false)
    }

    LaunchedEffect(forceRescanKey) {
        if (forceRescanKey > 0) explore.requestRemoteScan(force = true)
    }

    val hits = remember(query, remoteOfferings, allSongs) {
        explore.searchLive(query)
    }
    val albumHits = remember(query, allSongs) {
        if (query.trim().isEmpty()) emptyList()
        else library.albums(query.trim(), taggedOnly = false).take(12)
    }
    val artistHits = remember(query, allSongs) {
        if (query.trim().isEmpty()) emptyList()
        else library.artists(query.trim(), taggedOnly = false).take(12)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Search all libraries…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            )
        )

        val status = when {
            err != null && !scanning -> err!!
            scanning && scanProgress != null -> scanProgress!!
            scanning -> "Scanning remote libraries… ($indexed indexed)"
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
            if (scanning) {
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
                            "Search across every library",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        Text(
                            "Artists, albums, and songs · local + Jellyfin + Subsonic",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            hits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty() && !scanning -> {
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
                        item {
                            SectionHeader("Artists")
                        }
                        items(artistHits, key = { "ar-${it.name}" }) { artist ->
                            ExploreEntityRow(
                                title = artist.displayName,
                                subtitle = "${artist.trackCount} tracks · ${artist.albumCount} albums",
                                icon = Icons.Default.Person,
                                onClick = { onOpenArtist(artist) }
                            )
                        }
                    }
                    if (albumHits.isNotEmpty()) {
                        item {
                            SectionHeader("Albums")
                        }
                        items(albumHits, key = { "al-${it.name}-${it.artist}" }) { album ->
                            ExploreEntityRow(
                                title = album.displayName,
                                subtitle = album.displayArtist,
                                icon = Icons.Default.Album,
                                onClick = { onOpenAlbum(album) }
                            )
                        }
                    }
                    if (hits.isNotEmpty()) {
                        item {
                            SectionHeader("Songs")
                        }
                        itemsIndexed(hits, key = { _, h -> h.identityKey }) { index, hit ->
                            Column {
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
                                    showHeart = true
                                )
                                if (hit.isExplicit || hit.isMultiSource) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (hit.isMultiSource) Modifier.clickable { sourcesFor = hit }
                                                else Modifier
                                            )
                                            .padding(start = 68.dp, end = 16.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SongBadgeRow(
                                            isExplicit = hit.isExplicit,
                                            multiSource = hit.isMultiSource
                                        )
                                        if (hit.isMultiSource) {
                                            Text(
                                                hit.offerings.joinToString(" · ") { it.sourceName } +
                                                    "  ·  tap to prioritize",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                                maxLines = 1
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

    sourcesFor?.let { hit ->
        SourcesPickerSheet(
            hit = hit,
            onDismiss = { sourcesFor = null },
            onPick = { off ->
                scope.launch {
                    explore.setPreferredSource(hit.identityKey, off)
                    sourcesFor = null
                    Toast.makeText(context, "Preferred: ${off.sourceName}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
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
private fun ExploreEntityRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesPickerSheet(
    hit: ExploreSearchService.Hit,
    onDismiss: () -> Unit,
    onPick: (SourceOffering) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Text(
            "Sources",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Text(
            hit.song.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        hit.offerings.forEach { off ->
            val preferred =
                off.sourceId == hit.preferred.sourceId &&
                    off.sourceType == hit.preferred.sourceType &&
                    off.song.songKey == hit.preferred.song.songKey
            MediaSheetItem(
                label = buildString {
                    append(off.sourceName)
                    append(" · ")
                    append(off.sourceType.name.lowercase().replaceFirstChar { it.titlecase() })
                    if (preferred) append("  ✓ preferred")
                }
            ) { onPick(off) }
        }
        MediaSheetBottomPad()
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
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        }
    }
}
