package capital.yuri.yuriplayer.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.components.model.albums
import capital.yuri.yuriplayer.components.model.toRow
import capital.yuri.yuriplayer.core.library.LocalLibraryScanner
import capital.yuri.yuriplayer.core.library.matchesQuery
import capital.yuri.yuriplayer.core.library.matchesSearch
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.desktop.DesktopPlaylist
import capital.yuri.yuriplayer.desktop.DesktopSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MS = 280L
private const val SONG_HIT_LIMIT = 80
private const val ALBUM_HIT_LIMIT = 12
private const val ARTIST_HIT_LIMIT = 12

@Composable
fun DesktopExplore(
    session: DesktopSession,
    query: String,
    tracks: List<Song>,
    albums: List<AlbumPageModel>,
    playlists: List<DesktopPlaylist>,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    likedIds: Set<String> = emptySet(),
    onToggleLike: (String) -> Unit = {},
    songMenu: (Song) -> List<out MenuEntry> = { emptyList() }
) {
    val remotes by session.sources.remotes.collectAsState()
    val enabled = remember(remotes) { remotes.filter { it.enabled } }
    val allKeys = remember(enabled) {
        buildSet {
            add(LocalLibraryScanner.SOURCE_LOCAL)
            enabled.forEach { add(it.id) }
        }
    }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    val effective = if (selectedKeys.isEmpty()) allKeys else selectedKeys

    var songHits by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albumHits by remember { mutableStateOf<List<AlbumPageModel>>(emptyList()) }
    var artistHits by remember { mutableStateOf<List<ArtistHit>>(emptyList()) }
    var playlistHits by remember { mutableStateOf<List<DesktopPlaylist>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // Cache the artist→tracks grouping across keystrokes (it only changes when
    // the library changes, not when the query changes).
    val tracksByArtist = remember(tracks) { tracks.groupBy { it.displayArtist.lowercase() } }

    LaunchedEffect(query, selectedKeys, tracks, albums, playlists, enabled) {
        val q = query.trim()
        if (q.isEmpty()) {
            songHits = emptyList()
            albumHits = emptyList()
            artistHits = emptyList()
            playlistHits = emptyList()
            busy = false
            return@LaunchedEffect
        }
        busy = true
        delay(SEARCH_DEBOUNCE_MS)
        if (query.trim() != q) return@LaunchedEffect

        val includeLocal = selectedKeys.isEmpty() || LocalLibraryScanner.SOURCE_LOCAL in selectedKeys
        val remoteIds = if (selectedKeys.isEmpty()) null else selectedKeys - LocalLibraryScanner.SOURCE_LOCAL

        val indexed = tracks.filter { track ->
            val sid = track.sourceId ?: LocalLibraryScanner.SOURCE_LOCAL
            sid in effective
        }

        // Compute the full matching set once. The previous code re-ran
        // matchesQuery() inside the album loop (O(albums × tracks)), which was
        // the source of the lag; now it runs once per track.
        val matching = withContext(Dispatchers.Default) {
            indexed.filter { it.matchesQuery(q) }
        }
        if (query.trim() != q) return@LaunchedEffect

        val matchingAlbumTitles = matching.mapTo(HashSet()) { it.displayAlbum.lowercase() }

        fun applyHits(songs: List<Song>) {
            val unique = songs.distinctBy { it.songKey }.take(SONG_HIT_LIMIT)
            songHits = unique
            val uniqueIds = unique.mapTo(HashSet()) { it.songKey }

            albumHits = albums.filter { album ->
                album.title.matchesSearch(q) ||
                    album.artist.matchesSearch(q) ||
                    album.tracks.any { row -> row.id in uniqueIds } ||
                    album.title.lowercase() in matchingAlbumTitles
            }.take(ALBUM_HIT_LIMIT).ifEmpty { unique.albums().take(ALBUM_HIT_LIMIT) }

            artistHits = indexed
                .map { it.displayArtist }
                .distinct()
                .filter { it.matchesSearch(q) }
                .take(ARTIST_HIT_LIMIT)
                .mapNotNull { name ->
                    val ofArtist = tracksByArtist[name.lowercase()].orEmpty()
                    if (ofArtist.isEmpty()) null
                    else ArtistHit(
                        name = name,
                        artworkUri = ofArtist.firstNotNullOfOrNull { it.albumArtUri },
                        trackCount = ofArtist.size,
                        albumCount = ofArtist.map { it.displayAlbum }.distinct().size
                    )
                }

            playlistHits = if (includeLocal) {
                playlists.filter { it.name.matchesSearch(q) }.take(12)
            } else {
                emptyList()
            }
            busy = false
        }

        applyHits(matching)

        val remoteMatches = if (remoteIds == null || remoteIds.isNotEmpty()) {
            runCatching {
                withContext(Dispatchers.IO) { session.searchRemotes(q, remoteIds) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (query.trim() != q) return@LaunchedEffect
        applyHits(matching + remoteMatches)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Filters",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 4.dp)
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedKeys.isEmpty(),
                    onClick = { selectedKeys = emptySet() },
                    label = { Text("All", maxLines = 1) }
                )
            }
            item {
                FilterChip(
                    selected = selectedKeys.isEmpty() || LocalLibraryScanner.SOURCE_LOCAL in selectedKeys,
                    onClick = {
                        selectedKeys = toggleKey(
                            LocalLibraryScanner.SOURCE_LOCAL, allKeys, selectedKeys
                        )
                    },
                    label = { Text("On this device", maxLines = 1) }
                )
            }
            items(enabled, key = { it.id }) { remote ->
                FilterChip(
                    selected = selectedKeys.isEmpty() || remote.id in selectedKeys,
                    onClick = { selectedKeys = toggleKey(remote.id, allKeys, selectedKeys) },
                    label = { Text(remote.name.ifBlank { remote.kind.name }, maxLines = 1) }
                )
            }
        }

        val status = when {
            busy -> "Searching…"
            query.trim().isEmpty() -> "Type to search songs, albums, artists, and playlists"
            else -> buildList {
                if (artistHits.isNotEmpty()) add("${artistHits.size} artists")
                if (albumHits.isNotEmpty()) add("${albumHits.size} albums")
                if (playlistHits.isNotEmpty()) add("${playlistHits.size} playlists")
                add("${songHits.size} songs")
            }.joinToString(" · ")
        }
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        when {
            query.trim().isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Spacer(Modifier.height(12.dp))
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
            !busy && songHits.isEmpty() && albumHits.isEmpty() &&
                artistHits.isEmpty() && playlistHits.isEmpty() -> {
                Text(
                    if (tracks.isEmpty()) {
                        "Nothing indexed yet — scan a folder from the globe menu or Settings → Library."
                    } else {
                        "No matches for \"${query.trim()}\"."
                    },
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (artistHits.isNotEmpty()) {
                        item { ExploreSection("Artists") }
                        items(artistHits, key = { "ar-${it.name}" }) { artist ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenArtist(artist.name) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArt(
                                    model = artist.artworkUri,
                                    size = 48.dp,
                                    corner = 24.dp,
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(artist.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        "${artist.trackCount} tracks · ${artist.albumCount} albums",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                    }
                    if (albumHits.isNotEmpty()) {
                        item { ExploreSection("Albums") }
                        items(albumHits, key = { "al-${it.id}" }) { album ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenAlbum(album) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArt(model = album.artworkUri, size = 48.dp, corner = 6.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(album.title, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        album.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (playlistHits.isNotEmpty()) {
                        item { ExploreSection("Playlists") }
                        items(playlistHits, key = { "pl-${it.id}" }) { pl ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenPlaylist(pl) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArt(
                                    model = pl.artworkUri(tracks),
                                    size = 48.dp,
                                    corner = 6.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(pl.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        pl.description ?: "Playlist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (songHits.isNotEmpty()) {
                        item { ExploreSection("Songs") }
                        itemsIndexed(songHits, key = { _, t -> t.songKey }) { index, track ->
                            TrackRow(
                                track = track.toRow(),
                                onClick = { onPlaySongs(songHits, index) },
                                liked = track.songKey in likedIds,
                                onToggleLike = { onToggleLike(track.songKey) },
                                contextItems = songMenu(track)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ExploreSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

private data class ArtistHit(
    val name: String,
    val artworkUri: String?,
    val trackCount: Int,
    val albumCount: Int
)

private fun toggleKey(key: String, allKeys: Set<String>, selected: Set<String>): Set<String> {
    val effective = if (selected.isEmpty()) allKeys else selected
    val next = if (key in effective) effective - key else effective + key
    return if (next.isEmpty() || next.containsAll(allKeys)) emptySet() else next
}
