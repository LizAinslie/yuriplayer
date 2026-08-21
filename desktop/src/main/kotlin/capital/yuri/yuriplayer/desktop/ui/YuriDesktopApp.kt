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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.album.AlbumPage
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.layout.DesktopBentoLayout
import capital.yuri.yuriplayer.components.layout.DesktopNav
import capital.yuri.yuriplayer.components.layout.LibraryFilter
import capital.yuri.yuriplayer.components.layout.LibraryRailItem
import capital.yuri.yuriplayer.components.list.AlbumCard
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.components.model.albums
import capital.yuri.yuriplayer.components.model.toCover
import capital.yuri.yuriplayer.components.model.toRow
import capital.yuri.yuriplayer.components.player.BottomPlayerBar
import capital.yuri.yuriplayer.components.player.NowPlayingSidebar
import capital.yuri.yuriplayer.components.theme.PlayerChromeTheme
import capital.yuri.yuriplayer.components.theme.YuriTheme
import capital.yuri.yuriplayer.components.theme.playerColorsFromPixels
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.desktop.DesktopCollection
import capital.yuri.yuriplayer.desktop.DesktopSession

private sealed class Route {
    data object Home : Route()
    data object Search : Route()
    data class Album(val album: AlbumPageModel) : Route()
    data class Playlist(val id: String) : Route()
}

@Composable
fun YuriDesktopApp(session: DesktopSession) {
    val coverPixels by session.coverPixels.collectAsState()
    val playerColors = remember(coverPixels) {
        coverPixels?.let { playerColorsFromPixels(it) }
    }
    val themeChoice by session.theme.choice.collectAsState()
    YuriTheme(choice = themeChoice) {
        val tracks by session.tracks.collectAsState()
        val current by session.player.current.collectAsState()
        val playing by session.player.isPlaying.collectAsState()
        val queue by session.player.queue.collectAsState()
        val history by session.player.history.collectAsState()
        val status by session.scanMessage.collectAsState()
        val position by session.positionMs.collectAsState()
        val duration by session.durationMs.collectAsState()
        val engineMessage by session.engineMessage.collectAsState()
        val shuffle by session.player.shuffle.collectAsState()
        val repeat by session.player.repeat.collectAsState()
        val volume by session.player.volume.collectAsState()
        val liked by session.collection.liked.collectAsState()
        val pins by session.collection.pinned.collectAsState()
        val playlists by session.playlists.playlists.collectAsState()
        val leftFrac by session.layout.leftFraction.collectAsState()
        val rightFrac by session.layout.rightFraction.collectAsState()
        val albums = remember(tracks) { tracks.albums() }
        var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
        var forward by remember { mutableStateOf(listOf<Route>()) }
        var query by remember { mutableStateOf("") }
        var libraryFilter by remember { mutableStateOf(LibraryFilter.Recents) }
        var showSettings by remember { mutableStateOf(false) }
        var showSidebar by remember { mutableStateOf(true) }
        var editSong by remember { mutableStateOf<Track?>(null) }
        var editAlbum by remember { mutableStateOf<AlbumPageModel?>(null) }
        val route = stack.last()
        val nav = if (route is Route.Search) DesktopNav.Search else DesktopNav.Home

        fun push(next: Route) {
            stack = stack + next
            forward = emptyList()
        }

        fun goBack() {
            if (stack.size > 1) {
                forward = listOf(stack.last()) + forward
                stack = stack.dropLast(1)
            }
        }

        fun goForward() {
            val n = forward.firstOrNull() ?: return
            forward = forward.drop(1)
            stack = stack + n
        }

        fun openAlbum(album: AlbumPageModel) = push(Route.Album(album))

        val recency = remember(history) {
            history.mapIndexed { i, t -> t.id to i }.toMap()
        }
        fun albumRecency(album: AlbumPageModel): Int =
            album.tracks.minOfOrNull { recency[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE

        val libraryItems = remember(albums, tracks, libraryFilter, pins, history, liked, playlists) {
            val pinItems = pins.map { pin ->
                when (pin.kind) {
                    DesktopCollection.Kind.ALBUM -> {
                        val album = albums.firstOrNull { it.id == pin.id }
                        LibraryRailItem(
                            pin.id, pin.title, pin.subtitle,
                            album?.artworkUri, pinned = true
                        )
                    }
                    DesktopCollection.Kind.ARTIST -> LibraryRailItem(
                        pin.id, pin.title, pin.subtitle,
                        tracks.firstOrNull { it.displayArtist == pin.title }?.artworkUri,
                        circular = true, pinned = true
                    )
                    DesktopCollection.Kind.SONG -> {
                        val t = tracks.firstOrNull { it.id == pin.id }
                        LibraryRailItem(
                            pin.id, pin.title, pin.subtitle,
                            t?.artworkUri, pinned = true
                        )
                    }
                    DesktopCollection.Kind.PLAYLIST -> {
                        val pl = playlists.firstOrNull { it.id == pin.id }
                        LibraryRailItem(
                            "playlist:${pin.id}", pin.title, pin.subtitle,
                            pl?.artworkUri(tracks), pinned = true
                        )
                    }
                }
            }
            val rest = when (libraryFilter) {
                LibraryFilter.Artists -> tracks
                    .map { it.displayArtist }
                    .distinct()
                    .map { name ->
                        val cover = tracks.firstOrNull { it.displayArtist == name }?.artworkUri
                        val rec = tracks.filter { it.displayArtist == name }
                            .minOfOrNull { recency[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
                        Triple(rec, name, cover)
                    }
                    .sortedBy { it.first }
                    .map { (_, name, cover) ->
                        LibraryRailItem("artist:$name", name, "Artist", cover, circular = true)
                    }
                LibraryFilter.Albums -> albums
                    .sortedBy { albumRecency(it) }
                    .map { LibraryRailItem(it.id, it.title, it.artist, it.artworkUri) }
                LibraryFilter.Playlists -> listOf(
                    LibraryRailItem("new-playlist", "New playlist", "Create", null)
                ) + playlists.sortedByDescending { it.updatedAtMs }.map { pl ->
                    LibraryRailItem(
                        "playlist:${pl.id}",
                        pl.name,
                        "${pl.trackIds.size} songs",
                        pl.artworkUri(tracks)
                    )
                }
                LibraryFilter.Recents -> albums
                    .sortedBy { albumRecency(it) }
                    .map {
                        LibraryRailItem(
                            it.id, it.title,
                            "Album · ${it.artist}",
                            it.artworkUri
                        )
                    }
            }
            val pinIds = pinItems.map { it.id }.toSet()
            pinItems + rest.filter { it.id !in pinIds }
        }
        val selectedLibraryId = (route as? Route.Album)?.album?.id
        val visibleAlbums = remember(albums, query) {
            if (query.isBlank()) albums
            else albums.filter {
                it.title.contains(query, true) || it.artist.contains(query, true)
            }
        }

        DesktopBentoLayout(
            nav = nav,
            searchQuery = query,
            onSearchQuery = {
                query = it
                if (it.isNotBlank() && route !is Route.Search) push(Route.Search)
            },
            onHome = { stack = listOf(Route.Home); forward = emptyList(); query = "" },
            onBack = ::goBack,
            onForward = ::goForward,
            canBack = stack.size > 1,
            canForward = forward.isNotEmpty(),
            onSettings = { showSettings = true },
            scanMenu = { DesktopScanMenu(session) },
            libraryFilter = libraryFilter,
            onLibraryFilter = { libraryFilter = it },
            libraryItems = libraryItems,
            selectedLibraryId = selectedLibraryId,
            onLibraryItem = { item ->
                when {
                    item.id == "new-playlist" -> {
                        val created = session.playlists.create("New playlist")
                        push(Route.Playlist(created.id))
                    }
                    item.id.startsWith("playlist:") -> {
                        push(Route.Playlist(item.id.removePrefix("playlist:")))
                    }
                    item.id.startsWith("artist:") -> {
                        query = item.title
                        push(Route.Search)
                    }
                    else -> albums.firstOrNull { it.id == item.id }?.let { openAlbum(it) }
                }
            },
            showSidebar = showSidebar,
            leftFraction = leftFrac,
            rightFraction = rightFrac,
            onLeftFraction = { f, persist -> session.layout.setLeft(f, persist) },
            onRightFraction = { f, persist -> session.layout.setRight(f, persist) },
            bottomBar = {
                BottomPlayerBar(
                    track = current?.toCover(),
                    playing = playing,
                    positionMs = position,
                    durationMs = duration,
                    onPrev = session.player::previous,
                    onToggle = session.player::togglePlay,
                    onNext = session.player::next,
                    onSeek = session.player::seekTo,
                    accent = playerColors?.accent ?: MaterialTheme.colorScheme.primary,
                    shuffle = shuffle,
                    repeat = repeat,
                    onToggleShuffle = session.player::toggleShuffle,
                    onCycleRepeat = session.player::cycleRepeat,
                    volume = volume,
                    onVolume = session.player::setVolume,
                    liked = current?.id in liked,
                    onToggleLike = {
                        val t = current ?: return@BottomPlayerBar
                        session.collection.toggleLike(t.id)
                        val album = albums.firstOrNull { a -> a.tracks.any { it.id == t.id } }
                        if (album != null && session.collection.isLiked(t.id)) {
                            session.collection.pin(
                                DesktopCollection.Pin(
                                    DesktopCollection.Kind.ALBUM,
                                    album.id,
                                    album.title,
                                    album.artist
                                )
                            )
                        }
                    },
                    queueVisible = showSidebar,
                    onToggleQueue = { showSidebar = !showSidebar }
                )
            },
            sidebar = {
                PlayerChromeTheme(playerColors, useArtBackground = true) {
                    NowPlayingSidebar(
                        track = current?.toCover(),
                        queue = queue.map { it.toRow(highlighted = it.id == current?.id) },
                        history = history.map { it.toRow() },
                        onQueueTrack = { row ->
                            session.player.playTrack(
                                queue.first { it.id == row.id },
                                queue
                            )
                        },
                        onHistoryTrack = { row ->
                            val t = history.firstOrNull { it.id == row.id } ?: return@NowPlayingSidebar
                            session.player.playTrack(t, tracks.ifEmpty { listOf(t) })
                        },
                        onClearQueue = session.player::clearQueueKeepCurrent,
                        onClearHistory = session.player::clearHistory,
                        onMoveUpcoming = { from, to ->
                            val cur = current ?: return@NowPlayingSidebar
                            val base = queue.indexOfFirst { it.id == cur.id }
                            if (base < 0) return@NowPlayingSidebar
                            session.player.moveQueueItem(base + 1 + from, base + 1 + to)
                        }
                    )
                }
            }
        ) {
            when (val r = route) {
                Route.Home -> HomeFeed(
                    albums = albums,
                    playlists = playlists,
                    tracks = tracks,
                    recents = history,
                    status = engineMessage ?: status,
                    onOpenAlbum = ::openAlbum,
                    onOpenPlaylist = { push(Route.Playlist(it.id)) },
                    onPlayRecent = { t -> session.player.playTrack(t, tracks.ifEmpty { listOf(t) }) }
                )
                Route.Search -> LibraryGrid(
                    albums = visibleAlbums,
                    status = if (query.isBlank()) "Search albums and artists" else "${visibleAlbums.size} matches",
                    emptyHint = if (query.isBlank()) "Type in the search bar." else "Nothing matches.",
                    onOpen = ::openAlbum
                )
                is Route.Album -> {
                    val live = albums.firstOrNull { it.id == r.album.id } ?: r.album
                    val albumTracks = live.tracks.mapNotNull { row ->
                        tracks.firstOrNull { it.id == row.id }
                    }
                    AlbumPage(
                        album = live.copy(
                            tracks = live.tracks.map {
                                it.copy(highlighted = it.id == current?.id)
                            }
                        ),
                        playing = playing && albumTracks.any { it.id == current?.id },
                        onBack = ::goBack,
                        onPlay = {
                            if (albumTracks.isNotEmpty()) session.player.play(albumTracks, 0)
                        },
                        onTrack = { i ->
                            if (i in albumTracks.indices) session.player.play(albumTracks, i)
                        },
                        onEdit = { editAlbum = live },
                        onEditTrack = { i ->
                            albumTracks.getOrNull(i)?.let { editSong = it }
                        }
                    )
                }
                is Route.Playlist -> {
                    val pl = playlists.firstOrNull { it.id == r.id }
                    if (pl == null) {
                        Text("Playlist gone.", modifier = Modifier.padding(24.dp))
                    } else {
                        PlaylistPage(
                            playlist = pl,
                            tracks = pl.tracks(tracks),
                            library = tracks,
                            currentId = current?.id,
                            store = session.playlists,
                            onBack = ::goBack,
                            onPlay = { list, i -> session.player.play(list, i) },
                            onEditTrack = { editSong = it }
                        )
                    }
                }
            }
        }
        if (showSettings) {
            DesktopSettingsDialog(
                session = session,
                onDismiss = { showSettings = false }
            )
        }
        editSong?.let { song ->
            EditSongDialog(
                track = song,
                onDismiss = { editSong = null },
                onSaved = { session.replaceTracks(listOf(it)) }
            )
        }
        editAlbum?.let { album ->
            val albumTracks = tracks.filter { t -> album.tracks.any { it.id == t.id } }
            EditAlbumDialog(
                album = album,
                tracks = albumTracks,
                onDismiss = { editAlbum = null },
                onSaved = { session.replaceTracks(it) }
            )
        }
    }
}

@Composable
private fun HomeFeed(
    albums: List<AlbumPageModel>,
    playlists: List<capital.yuri.yuriplayer.desktop.DesktopPlaylist>,
    tracks: List<Track>,
    recents: List<Track>,
    status: String,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onPlayRecent: (Track) -> Unit
) {
    val recentAlbums = remember(recents, albums) {
        recents.mapNotNull { t ->
            albums.firstOrNull { a -> a.tracks.any { it.id == t.id } }
        }.distinctBy { it.id }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("All") })
            FilterChip(selected = false, onClick = {}, label = { Text("Music") })
        }
        Spacer(Modifier.height(16.dp))
        if (recentAlbums.isNotEmpty() || recents.isNotEmpty()) {
            recentAlbums.take(8).chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { album ->
                        Surface(
                            modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenAlbum(album) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CoverArt(model = album.artworkUri, size = 64.dp, corner = 0.dp)
                                Text(
                                    album.title,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (recents.isNotEmpty()) {
            SectionHeader("Recents")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recents.take(12), key = { it.id }) { track ->
                    AlbumCard(
                        album = AlbumPageModel(
                            id = track.id,
                            title = track.displayTitle,
                            artist = track.displayArtist,
                            artworkUri = track.artworkUri,
                            tracks = emptyList()
                        ),
                        onClick = { onPlayRecent(track) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (playlists.isNotEmpty()) {
            SectionHeader("Playlists")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists.take(12), key = { it.id }) { pl ->
                    AlbumCard(
                        album = AlbumPageModel(
                            id = pl.id,
                            title = pl.name,
                            artist = pl.description ?: "Playlist",
                            artworkUri = pl.artworkUri(tracks),
                            tracks = emptyList()
                        ),
                        onClick = { onOpenPlaylist(pl) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (albums.isNotEmpty()) {
            SectionHeader("Albums")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums.shuffled().take(12), key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }
            Spacer(Modifier.height(24.dp))
            val artists = albums.map { it.artist }.distinct().take(12)
            SectionHeader("Artists")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(artists, key = { it }) { name ->
                    val cover = albums.firstOrNull { it.artist == name }?.artworkUri
                    Column(
                        Modifier.width(140.dp).clickable {
                            albums.firstOrNull { it.artist == name }?.let(onOpenAlbum)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CoverArt(model = cover, size = 128.dp, corner = 64.dp)
                        Text(
                            name,
                            modifier = Modifier.padding(top = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            Text(
                "Nothing in the default music folders yet.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {}) { Text("Show all") }
    }
}

@Composable
private fun LibraryGrid(
    albums: List<AlbumPageModel>,
    status: String,
    emptyHint: String,
    onOpen: (AlbumPageModel) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)
        )
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyHint, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpen(album) })
                }
            }
        }
    }
}
