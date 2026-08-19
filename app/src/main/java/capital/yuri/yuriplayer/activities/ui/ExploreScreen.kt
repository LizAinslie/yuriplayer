package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
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
import capital.yuri.yuriplayer.data.ExploreSearchService
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.source.SourceOffering
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ExploreScreen(
    nowPlaying: Song? = null,
    isPlaybackActive: Boolean = false,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    forceRescanKey: Int = 0
) {
    val explore: ExploreSearchService = koinInject()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ExploreSearchService.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var sourcesFor by remember { mutableStateOf<ExploreSearchService.Hit?>(null) }

    val scanning by explore.isScanning.collectAsState()
    val err by explore.lastError.collectAsState()
    val remoteSources by explore.sourceCount.collectAsState()

    LaunchedEffect(forceRescanKey) {
        if (forceRescanKey > 0) {
            explore.refreshRemotes()
            if (query.trim().isNotEmpty()) {
                searching = true
                hits = explore.search(query, forceRescan = false)
                searching = false
            }
        }
    }

    fun runSearch(q: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val trimmed = q.trim()
            if (trimmed.isEmpty()) {
                hits = emptyList()
                searching = false
                return@launch
            }
            delay(280)
            searching = true
            hits = explore.search(trimmed)
            searching = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                runSearch(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        hits = emptyList()
                    }) {
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
                    runSearch(query)
                }
            )
        )

        val status = when {
            err != null -> err!!
            scanning -> "Scanning remote libraries…"
            query.trim().isEmpty() -> {
                if (remoteSources > 0) "Type to search local + $remoteSources remote source(s)"
                else "Type to search your library"
            }
            searching -> "Searching…"
            else -> "${hits.size} result${if (hits.size == 1) "" else "s"}"
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scanning || searching) {
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
                            "Local files, Jellyfin, Subsonic — results appear as you type.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            hits.isEmpty() && !searching && !scanning -> {
                Text(
                    "No matches for \"${query.trim()}\".",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            else -> {
                val songs = hits.map { it.preferred.song }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                showHeart = true,
                                onEditMetadata = if (hit.isMultiSource) {
                                    { sourcesFor = hit }
                                } else null
                            )
                            if (hit.isExplicit || hit.isMultiSource) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                            hit.offerings.joinToString(" · ") { it.sourceName },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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

    sourcesFor?.let { hit ->
        SourcesPickerSheet(
            hit = hit,
            onDismiss = { sourcesFor = null },
            onPick = { off ->
                scope.launch {
                    explore.setPreferredSource(hit.identityKey, off)
                    hits = explore.search(query)
                    sourcesFor = null
                    Toast.makeText(context, "Preferred: ${off.sourceName}", Toast.LENGTH_SHORT).show()
                }
            }
        )
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
            val preferred = off === hit.preferred ||
                (off.sourceId == hit.preferred.sourceId &&
                    off.sourceType == hit.preferred.sourceType &&
                    off.song.songKey == hit.preferred.song.songKey)
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

/** Tiny “E” badge (Spotify-style) and multi-source cloud pip. */
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
