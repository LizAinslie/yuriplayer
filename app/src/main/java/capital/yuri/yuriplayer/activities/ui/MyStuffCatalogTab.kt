package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import capital.yuri.yuriplayer.data.source.LibraryFaviconStore
import capital.yuri.yuriplayer.data.source.RemotePlaylist
import capital.yuri.yuriplayer.data.source.RemotePlaylistService
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.ui.TestTags
import capital.yuri.yuriplayer.ui.formatTrackCount
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

private sealed class CatalogPage {
    data object Hub : CatalogPage()
    data object Artists : CatalogPage()
    data object Albums : CatalogPage()
    data object Playlists : CatalogPage()
    data object Songs : CatalogPage()
    data class Library(
        val name: String,
        val sourceType: String,
        val instanceId: Long?
    ) : CatalogPage()
}

@Composable
fun MyStuffCatalogTab(
    entries: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    nowPlaying: Song?,
    isPlaybackActive: Boolean,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    val catalog: CatalogRepository = koinInject()
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val favicons: LibraryFaviconStore = koinInject()
    val remotePlaylists: RemotePlaylistService = koinInject()
    val sources by sourcesRepo.observeAll().collectAsState(initial = emptyList())
    val allSongs by library.songs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf<CatalogPage>(CatalogPage.Hub) }
    var resolvedArtists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var resolvedAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var resolvedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var remotePls by remember { mutableStateOf<List<RemotePlaylist>>(emptyList()) }

    val artistPins = remember(entries) { entries.filter { it.kind == StuffPinKind.ARTIST } }
    val albumPins = remember(entries) { entries.filter { it.kind == StuffPinKind.ALBUM } }
    val songPins = remember(entries) { entries.filter { it.kind == StuffPinKind.SONG } }

    LaunchedEffect(artistPins, albumPins, songPins, allSongs) {
        resolvedArtists = withContext(Dispatchers.IO) {
            artistPins.mapNotNull { pin ->
                catalog.artistItemForKey(pin.id, pin.title)
                    ?: library.artists(taggedOnly = false)
                        .firstOrNull { artistKey(it.name) == pin.id }
                    ?: ArtistItem(name = pin.title, trackCount = 0, albumCount = 0, songs = emptyList())
            }
        }
        resolvedAlbums = withContext(Dispatchers.IO) {
            albumPins.mapNotNull { pin ->
                catalog.albumItemForKey(pin.id)
                    ?: library.albums(taggedOnly = false)
                        .firstOrNull { albumKey(it.name, it.artist) == pin.id }
            }
        }
        val keys = songPins.map { it.id }
        val fromCatalog = withContext(Dispatchers.IO) { catalog.getSongsByKeys(keys) }
        val byKey = fromCatalog.associateBy { it.songKey }
        resolvedSongs = songPins.mapNotNull { pin ->
            byKey[pin.id] ?: allSongs.firstOrNull { it.songKey == pin.id }
        }
    }

    LaunchedEffect(sources) {
        remotePls = withContext(Dispatchers.IO) { remotePlaylists.listAll() }
    }

    BackHandler(enabled = page !is CatalogPage.Hub) { page = CatalogPage.Hub }

    when (val p = page) {
        CatalogPage.Hub -> CatalogHub(
            artists = resolvedArtists,
            albums = resolvedAlbums,
            songs = resolvedSongs,
            playlists = playlists,
            remotePlaylists = remotePls,
            sources = sources.filter { it.enabled },
            favicons = favicons,
            nowPlaying = nowPlaying,
            isPlaybackActive = isPlaybackActive,
            onShowAllArtists = { page = CatalogPage.Artists },
            onShowAllAlbums = { page = CatalogPage.Albums },
            onShowAllPlaylists = { page = CatalogPage.Playlists },
            onShowAllSongs = { page = CatalogPage.Songs },
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenPlaylist = onOpenPlaylist,
            onOpenSongAlbum = onOpenSongAlbum,
            onOpenLibrary = { page = it },
            onImportRemote = { remote ->
                scope.launch {
                    val created = withContext(Dispatchers.IO) { remotePlaylists.importToLocal(remote) }
                    if (created != null) {
                        Toast.makeText(context, "Added ${created.name}", Toast.LENGTH_SHORT).show()
                        onOpenPlaylist(created)
                    } else {
                        Toast.makeText(context, "Couldn't import playlist", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onAddToQueue = onAddToQueue
        )
        CatalogPage.Artists -> CatalogShowAll(
            title = "Artists",
            onBack = { page = CatalogPage.Hub }
        ) {
            MediaBrowser(
                lookup = mediaBrowserLookup(artists = resolvedArtists),
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                sections = listOf(MediaBrowserSection.Artists),
                onPlay = onPlay,
                onAddToQueue = onAddToQueue,
                onOpenArtist = onOpenArtist
            )
        }
        CatalogPage.Albums -> CatalogShowAll(
            title = "Albums",
            onBack = { page = CatalogPage.Hub }
        ) {
            MediaBrowser(
                lookup = mediaBrowserLookup(albums = resolvedAlbums),
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                sections = listOf(MediaBrowserSection.Albums),
                onPlay = onPlay,
                onAddToQueue = onAddToQueue,
                onOpenAlbum = onOpenAlbum
            )
        }
        CatalogPage.Playlists -> CatalogShowAll(
            title = "Playlists",
            onBack = { page = CatalogPage.Hub }
        ) {
            PlaylistShowAll(
                local = playlists,
                remote = remotePls,
                onOpenLocal = onOpenPlaylist,
                onImportRemote = { remote ->
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { remotePlaylists.importToLocal(remote) }
                        if (created != null) onOpenPlaylist(created)
                    }
                }
            )
        }
        CatalogPage.Songs -> CatalogShowAll(
            title = "Songs",
            onBack = { page = CatalogPage.Hub }
        ) {
            MediaBrowser(
                lookup = mediaBrowserLookup(songs = resolvedSongs),
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                sections = listOf(MediaBrowserSection.Songs),
                onPlay = onPlay,
                onAddToQueue = onAddToQueue,
                onOpenAlbum = onOpenAlbum,
                onSongClick = onOpenSongAlbum
            )
        }
        is CatalogPage.Library -> CatalogShowAll(
            title = p.name,
            onBack = { page = CatalogPage.Hub }
        ) {
            LibrarySourceBrowser(
                sourceType = p.sourceType,
                instanceId = p.instanceId,
                nowPlaying = nowPlaying,
                isPlaybackActive = isPlaybackActive,
                onPlay = onPlay,
                onAddToQueue = onAddToQueue,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onOpenSongAlbum = onOpenSongAlbum
            )
        }
    }
}

@Composable
private fun CatalogShowAll(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        content()
    }
}

@Composable
private fun CatalogHub(
    artists: List<ArtistItem>,
    albums: List<AlbumItem>,
    songs: List<Song>,
    playlists: List<Playlist>,
    remotePlaylists: List<RemotePlaylist>,
    sources: List<SourceInstanceEntity>,
    favicons: LibraryFaviconStore,
    nowPlaying: Song?,
    isPlaybackActive: Boolean,
    onShowAllArtists: () -> Unit,
    onShowAllAlbums: () -> Unit,
    onShowAllPlaylists: () -> Unit,
    onShowAllSongs: () -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit,
    onOpenLibrary: (CatalogPage.Library) -> Unit,
    onImportRemote: (RemotePlaylist) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                "Catalog",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(TestTags.CATALOG_TITLE)
            )
        }
        item {
            LibraryChipsRow(
                sources = sources,
                favicons = favicons,
                onOpenLocal = {
                    onOpenLibrary(
                        CatalogPage.Library("On this device", CatalogSources.LOCAL, null)
                    )
                },
                onOpenSource = { row ->
                    onOpenLibrary(CatalogPage.Library(row.name, row.type, row.id))
                }
            )
        }
        item {
            CatalogSectionHeader("Artists", artists.size, onShowAllArtists)
        }
        if (artists.isEmpty()) {
            item { EmptyHint("Heart artists to keep them here.") }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(artists, key = { artistKey(it.name) ?: it.displayName }) { artist ->
                        CatalogArtistCard(artist, onClick = { onOpenArtist(artist) })
                    }
                }
            }
        }
        item {
            CatalogSectionHeader("Albums", albums.size, onShowAllAlbums)
        }
        if (albums.isEmpty()) {
            item { EmptyHint("Heart albums to keep them here.") }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(albums, key = { albumKey(it.name, it.artist) }) { album ->
                        CatalogAlbumCard(album, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }
        item {
            CatalogSectionHeader(
                "Playlists",
                playlists.size + remotePlaylists.size,
                onShowAllPlaylists
            )
        }
        if (playlists.isEmpty() && remotePlaylists.isEmpty()) {
            item { EmptyHint("Local playlists live here. Remote ones can be added from a source.") }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(playlists, key = { "local-${it.id}" }) { pl ->
                        CatalogPlaylistCard(
                            title = pl.name,
                            subtitle = formatTrackCount(pl.trackCount),
                            onClick = { onOpenPlaylist(pl) }
                        ) { PlaylistCoverArt(pl, size = 120.dp) }
                    }
                    items(remotePlaylists, key = { it.stableId }) { remote ->
                        CatalogPlaylistCard(
                            title = remote.name,
                            subtitle = "${remote.sourceName} · add",
                            onClick = { onImportRemote(remote) }
                        ) {
                            if (!remote.coverUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = remote.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(120.dp).padding(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            CatalogSectionHeader("Songs", songs.size, onShowAllSongs)
        }
        if (songs.isEmpty()) {
            item { EmptyHint("Heart songs to keep them here.") }
        } else {
            itemsIndexed(songs.take(12), key = { _, s -> s.songKey }) { _, song ->
                SwipeAddSongRow(
                    song = song,
                    onClick = { onOpenSongAlbum(song) },
                    onSwipeAdd = { onAddToQueue(song) },
                    showTrackNumber = false,
                    isPlaying = song.isSameAs(nowPlaying),
                    isPlaybackActive = isPlaybackActive,
                    showHeart = true
                )
            }
        }
    }
}

@Composable
private fun LibraryChipsRow(
    sources: List<SourceInstanceEntity>,
    favicons: LibraryFaviconStore,
    onOpenLocal: () -> Unit,
    onOpenSource: (SourceInstanceEntity) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = false,
                onClick = onOpenLocal,
                label = { Text("On this device") },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
        }
        items(sources, key = { it.id }) { row ->
            var icon by remember(row.id) { mutableStateOf<File?>(favicons.cachedFile(row.id)) }
            LaunchedEffect(row.id) {
                icon = favicons.ensure(row) ?: icon
            }
            FilterChip(
                selected = false,
                onClick = { onOpenSource(row) },
                label = { Text(row.name) },
                leadingIcon = {
                    if (icon != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(icon).build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun CatalogSectionHeader(title: String, count: Int, onShowAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            TextButton(onClick = onShowAll) { Text("Show all") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CatalogArtistCard(artist: ArtistItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtistArt(
            artistName = artist.displayName,
            seedSong = artist.songs.firstOrNull(),
            size = 88.dp,
            circular = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            artist.displayName,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CatalogAlbumCard(album: AlbumItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArt(song = album.songs.firstOrNull(), size = 120.dp, corner = 8.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            album.displayName,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            album.displayArtist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CatalogPlaylistCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        art()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistShowAll(
    local: List<Playlist>,
    remote: List<RemotePlaylist>,
    onOpenLocal: (Playlist) -> Unit,
    onImportRemote: (RemotePlaylist) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (local.isNotEmpty()) {
            item {
                Text(
                    "On this device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(local, key = { it.id }) { pl ->
                PlaylistRow(pl, onClick = { onOpenLocal(pl) })
            }
        }
        if (remote.isNotEmpty()) {
            item {
                Text(
                    "From libraries",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(remote, key = { it.stableId }) { remotePl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImportRemote(remotePl) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!remotePl.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = remotePl.coverUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.LibraryMusic, null, Modifier.size(56.dp).padding(12.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(remotePl.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${remotePl.sourceName} · tap to add to My Stuff",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySourceBrowser(
    sourceType: String,
    instanceId: Long?,
    nowPlaying: Song?,
    isPlaybackActive: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenSongAlbum: (Song) -> Unit
) {
    val catalog: CatalogRepository = koinInject()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(sourceType, instanceId) {
        songs = withContext(Dispatchers.IO) { catalog.songsForSource(sourceType, instanceId) }
    }
    val albums = remember(songs) {
        songs.filter { !it.album.isNullOrBlank() }
            .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
            .map { (_, tracks) ->
                AlbumItem(
                    name = tracks.first().album,
                    artist = tracks.first().effectiveAlbumArtist,
                    trackCount = tracks.size,
                    songs = tracks
                )
            }
    }
    MediaBrowser(
        lookup = mediaBrowserLookup(songs = songs, albums = albums),
        nowPlaying = nowPlaying,
        isPlaybackActive = isPlaybackActive,
        sections = listOf(MediaBrowserSection.Songs, MediaBrowserSection.Albums),
        statusLine = formatTrackCount(songs.size),
        onPlay = onPlay,
        onAddToQueue = onAddToQueue,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        onSongClick = onOpenSongAlbum
    )
}
