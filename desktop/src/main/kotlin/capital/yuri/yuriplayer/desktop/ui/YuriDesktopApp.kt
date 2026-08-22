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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.album.AlbumPage
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.artist.ArtistPage
import capital.yuri.yuriplayer.components.layout.DesktopBentoLayout
import capital.yuri.yuriplayer.components.layout.DesktopNav
import capital.yuri.yuriplayer.components.layout.LibraryFilter
import capital.yuri.yuriplayer.components.layout.LibraryRailItem
import capital.yuri.yuriplayer.components.list.AlbumCard
import capital.yuri.yuriplayer.components.list.ContextAction
import capital.yuri.yuriplayer.components.list.ContextMenuAnchor
import capital.yuri.yuriplayer.components.menu.ContextMenuScope
import capital.yuri.yuriplayer.components.menu.MenuEntry
import capital.yuri.yuriplayer.components.menu.buildContextMenu
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.components.model.TrackRowModel
import capital.yuri.yuriplayer.components.model.albums
import capital.yuri.yuriplayer.components.model.artistPage
import capital.yuri.yuriplayer.components.model.toCover
import capital.yuri.yuriplayer.components.model.toRow
import capital.yuri.yuriplayer.components.player.BottomPlayerBar
import capital.yuri.yuriplayer.components.player.NowPlayingSidebar
import capital.yuri.yuriplayer.components.theme.PlayerChromeTheme
import capital.yuri.yuriplayer.components.theme.YuriTheme
import capital.yuri.yuriplayer.components.theme.playerColorsFromPixels
import capital.yuri.yuriplayer.core.artist.ArtistProfile
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.library.albumPageIdentity
import capital.yuri.yuriplayer.core.library.pickPreferred
import capital.yuri.yuriplayer.core.library.sourceRank
import capital.yuri.yuriplayer.desktop.DesktopCollection
import capital.yuri.yuriplayer.desktop.DesktopSession
import kotlinx.coroutines.launch

private sealed class Route {
    data object Home : Route()
    data object Search : Route()
    data class Album(val album: AlbumPageModel) : Route()
    data class Playlist(val id: String) : Route()
    data class Artist(val name: String) : Route()
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
        val saved by session.collection.saved.collectAsState()
        val hotQueue by session.player.hotQueue.collectAsState()
        val coldQueue by session.player.coldQueue.collectAsState()
        val playlists by session.playlists.playlists.collectAsState()
        val remotes by session.sources.remotes.collectAsState()
        val leftFrac by session.layout.leftFraction.collectAsState()
        val rightFrac by session.layout.rightFraction.collectAsState()
        var prefRev by remember { mutableIntStateOf(0) }
        val albums = remember(tracks, prefRev) { tracks.albums(session.sourcePrefs.snapshot()) }
        var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
        var forward by remember { mutableStateOf(listOf<Route>()) }
        var query by remember { mutableStateOf("") }
        var libraryFilter by remember { mutableStateOf(LibraryFilter.Playlists) }
        var showSettings by remember { mutableStateOf(false) }
        var showNewPlaylist by remember { mutableStateOf(false) }
        var showSidebar by remember { mutableStateOf(true) }
        var editSong by remember { mutableStateOf<Track?>(null) }
        var editAlbum by remember { mutableStateOf<AlbumPageModel?>(null) }
        var addToPlaylist by remember { mutableStateOf<List<Track>?>(null) }
        var sourcesFor by remember { mutableStateOf<List<Track>?>(null) }
        val route = stack.last()
        val nav = if (route is Route.Search) DesktopNav.Search else DesktopNav.Home
        val scope = rememberCoroutineScope()

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
        fun openArtist(name: String) = push(Route.Artist(name))

        fun playlistAddAlternate(songs: List<Track>): ContextMenuScope.() -> Unit = {
            val candidates = playlists
                .filter { pl -> songs.any { t -> pl.trackIds.none { it in t.playlistKeys() } } }
                .sortedBy { it.name.lowercase() }
            if (candidates.isEmpty()) {
                item("Already in every playlist", enabled = false) {}
            } else {
                candidates.forEach { pl ->
                    item(pl.name) {
                        session.ensureTracks(songs)
                        session.playlists.addTracks(pl.id, songs)
                    }
                }
            }
        }

        fun songMenu(
            track: Track,
            onAlbumPage: Boolean = false,
            playlistId: String? = null
        ): List<MenuEntry> = buildContextMenu {
            item("Add to playlist", alternate = playlistAddAlternate(listOf(track))) {
                addToPlaylist = listOf(track)
            }
            item("Add to queue") { session.player.enqueue(track) }
            item("Sources") {
                val ids = HashSet<String>()
                ids += track.id
                ids += track.catalogKey()
                val row = albums.asSequence()
                    .mapNotNull { a -> a.tracks.firstOrNull { track.id in it.sourceIds || it.id == track.id } }
                    .firstOrNull()
                if (row != null) ids += row.sourceIds
                val want = ids
                sourcesFor = tracks.asSequence()
                    .filter { it.id in want || it.catalogKey() in want }
                    .distinctBy { it.id }
                    .take(16)
                    .toList()
                    .ifEmpty { listOf(track) }
            }
            submenu("Go to") {
                if (!onAlbumPage) {
                    item("Album") {
                        albums.firstOrNull { a ->
                            a.tracks.any { track.id in it.sourceIds || it.id == track.id }
                        }?.let(::openAlbum)
                    }
                }
                item("Artist") { openArtist(track.displayArtist) }
            }
            if (playlistId != null) {
                divider()
                item("Remove from this playlist", destructive = true) {
                    session.playlists.removeTracks(playlistId, listOf(track))
                }
            }
        }

        fun menuForQueueRow(row: TrackRowModel, pool: List<Track>): List<MenuEntry> {
            val track = pool.firstOrNull { it.id == row.id } ?: Track(
                id = row.id,
                uri = "",
                title = row.title,
                artist = row.artist,
                album = row.album,
                durationMs = row.durationMs,
                artworkUri = row.artworkUri
            )
            return songMenu(track)
        }

        fun pinTracks(item: LibraryRailItem): List<Track> = when {
            item.id == "liked" -> tracks.filter { it.id in liked }
            item.id.startsWith("playlist:") -> {
                val id = item.id.removePrefix("playlist:")
                playlists.firstOrNull { it.id == id }?.tracks(tracks).orEmpty()
            }
            item.id.startsWith("artist:") || item.circular ->
                tracks.filter {
                    it.displayArtist.equals(item.title, true) || it.albumArtist.equals(item.title, true)
                }
            else -> {
                val album = albums.firstOrNull { it.id == item.id }
                album?.tracks?.mapNotNull { row ->
                    val group = tracks.filter { it.id in row.sourceIds }
                    pickPreferred(group, session.sourcePrefs.get(albumPageIdentity(group.firstOrNull() ?: return@mapNotNull null)))
                }.orEmpty()
                    .ifEmpty { tracks.filter { it.id == item.id }.takeIf { it.isNotEmpty() } ?: emptyList() }
            }
        }

        val recency = remember(history) {
            history.mapIndexed { i, t -> t.id to i }.toMap()
        }
        fun albumRecency(album: AlbumPageModel): Int =
            album.tracks.minOfOrNull { recency[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE

        val libraryItems = remember(albums, tracks, libraryFilter, pins, saved, history, liked, playlists) {
            val likedAlbums = albums.filter { album -> album.tracks.any { it.id in liked } }
            val likedArtists = tracks.filter { it.id in liked }.map { it.displayArtist }.distinct()
            val pinItems = pins.map { pin ->
                when (pin.kind) {
                    DesktopCollection.Kind.ALBUM -> {
                        val album = albums.firstOrNull { it.id == pin.id }
                            ?: albums.firstOrNull { it.title.equals(pin.title, true) }
                        LibraryRailItem(
                            pin.id, pin.title, pin.subtitle,
                            album?.artworkUri, pinned = true
                        )
                    }
                    DesktopCollection.Kind.ARTIST -> LibraryRailItem(
                        pin.id, pin.title, pin.subtitle,
                        tracks.firstOrNull { it.displayArtist.equals(pin.title, true) }?.artworkUri,
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
            val likedRow = if (liked.isNotEmpty()) {
                listOf(
                    LibraryRailItem(
                        "liked",
                        "Liked Songs",
                        "${liked.size} songs",
                        tracks.firstOrNull { it.id in liked }?.artworkUri
                    )
                )
            } else emptyList()
            val rest = when (libraryFilter) {
                LibraryFilter.Artists -> {
                    val names = (
                        pins.filter { it.kind == DesktopCollection.Kind.ARTIST }.map { it.title } +
                            saved.filter { it.kind == DesktopCollection.Kind.ARTIST }.map { it.title } +
                            likedArtists
                        ).distinct()
                    names.map { name ->
                        LibraryRailItem(
                            "artist:$name",
                            name,
                            "Artist",
                            tracks.firstOrNull { it.displayArtist.equals(name, true) }?.artworkUri,
                            circular = true
                        )
                    }
                }
                LibraryFilter.Albums -> {
                    val pinnedAlbums = (
                        pins.filter { it.kind == DesktopCollection.Kind.ALBUM } +
                            saved.filter { it.kind == DesktopCollection.Kind.ALBUM }
                        )
                        .mapNotNull { pin ->
                            albums.firstOrNull { it.id == pin.id }
                                ?: albums.firstOrNull { it.title.equals(pin.title, true) }
                        }
                    (pinnedAlbums + likedAlbums).distinctBy { it.id }
                        .map { LibraryRailItem(it.id, it.title, it.artist, it.artworkUri) }
                }
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
                LibraryFilter.Recents -> {
                    val stuffAlbumIds = (
                        pins.filter { it.kind == DesktopCollection.Kind.ALBUM }.map { it.id } +
                            likedAlbums.map { it.id }
                        ).toSet()
                    val stuffPlaylistIds = playlists.map { it.id }.toSet()
                    buildList {
                        history.forEach { t ->
                            val album = albums.firstOrNull { a -> a.tracks.any { it.id == t.id } }
                            if (album != null && (album.id in stuffAlbumIds || album.tracks.any { it.id in liked })) {
                                add(LibraryRailItem(album.id, album.title, "Album · ${album.artist}", album.artworkUri))
                            }
                        }
                    }.distinctBy { it.id }
                }
            }
            val pinIds = pinItems.map { it.id }.toSet()
            likedRow + pinItems + rest.filter { it.id !in pinIds && it.id !in likedRow.map { r -> r.id }.toSet() }
        }
        val selectedLibraryId = (route as? Route.Album)?.album?.id

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
                    item.id == "new-playlist" -> showNewPlaylist = true
                    item.id == "liked" -> {
                        val likedTracks = tracks.filter { it.id in liked }
                        if (likedTracks.isNotEmpty()) session.player.play(likedTracks, 0)
                    }
                    item.id.startsWith("playlist:") -> {
                        push(Route.Playlist(item.id.removePrefix("playlist:")))
                    }
                    item.id.startsWith("artist:") -> {
                        openArtist(item.id.removePrefix("artist:").ifBlank { item.title })
                    }
                    item.circular -> openArtist(item.title)
                    else -> albums.firstOrNull { it.id == item.id }?.let { openAlbum(it) }
                }
            },
            libraryMenu = { item ->
                if (item.id == "new-playlist") {
                    emptyList()
                } else {
                    val playable = pinTracks(item)
                    buildList {
                        if (playable.isNotEmpty()) {
                            add(ContextAction("Play") { session.player.play(playable, 0) })
                            add(ContextAction("Add to queue") { session.player.enqueueAll(playable) })
                        }
                        if (playable.isNotEmpty()) {
                            add(
                                ContextAction(
                                    label = "Add to playlist",
                                    alternate = buildContextMenu(playlistAddAlternate(playable)),
                                    onClick = { addToPlaylist = playable }
                                )
                            )
                        }
                        if (item.pinned) {
                            val kind = when {
                                item.id.startsWith("playlist:") -> DesktopCollection.Kind.PLAYLIST
                                item.circular || item.id.startsWith("artist:") -> DesktopCollection.Kind.ARTIST
                                albums.any { it.id == item.id } -> DesktopCollection.Kind.ALBUM
                                else -> DesktopCollection.Kind.SONG
                            }
                            val rawId = item.id.removePrefix("playlist:").removePrefix("artist:")
                            add(
                                ContextAction("Unpin", destructive = true) {
                                    session.collection.unpin(kind, rawId)
                                }
                            )
                        }
                    }
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
                    onSeek = { ms ->
                        session.player.seekTo(ms)
                        session.persistPlayback()
                    },
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
                    },
                    queueVisible = showSidebar,
                    onToggleQueue = { showSidebar = !showSidebar },
                    songMenu = current?.let { songMenu(it) }.orEmpty()
                )
            },
            sidebar = {
                PlayerChromeTheme(playerColors, useArtBackground = true) {
                    val curId = current?.id
                    val hotIdx = hotQueue.indexOfFirst { it.id == curId }
                    val coldIdx = coldQueue.indexOfFirst { it.id == curId }
                    val hotUp = if (hotIdx >= 0) hotQueue.drop(hotIdx + 1) else hotQueue
                    val coldUp = if (coldIdx >= 0) coldQueue.drop(coldIdx + 1) else coldQueue
                    val coldLabel = coldUp.firstOrNull()?.displayAlbum?.takeIf { it.isNotBlank() && it != "Unknown Album" }
                        ?: "Up next"
                    NowPlayingSidebar(
                        track = current?.toCover(),
                        hot = hotUp.map { it.toRow() },
                        cold = coldUp.map { it.toRow() },
                        coldLabel = coldLabel,
                        history = history.map { it.toRow() },
                        onPlayHot = { i -> hotUp.getOrNull(i)?.let { session.player.playTrack(it) } },
                        onPlayCold = { i -> coldUp.getOrNull(i)?.let { session.player.playTrack(it) } },
                        onHistoryTrack = { row ->
                            val t = history.firstOrNull { it.id == row.id } ?: return@NowPlayingSidebar
                            session.player.playTrack(t, tracks.ifEmpty { listOf(t) })
                        },
                        onClearHot = session.player::clearHot,
                        onClearHistory = session.player::clearHistory,
                        onMoveHot = { from, to ->
                            val skip = if (hotIdx >= 0) hotIdx + 1 else 0
                            session.player.moveHot(from + skip, to + skip)
                        },
                        onMoveCold = { from, to ->
                            val skip = if (coldIdx >= 0) coldIdx + 1 else 0
                            session.player.moveCold(from + skip, to + skip)
                        },
                        likedIds = liked,
                        onToggleTrackLike = session.collection::toggleLike,
                        songMenu = { row ->
                            menuForQueueRow(row, hotQueue + coldQueue + history + tracks)
                        },
                        nowPlayingMenu = current?.let { songMenu(it) }.orEmpty()
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
                    pins = pins,
                    liked = liked,
                    status = engineMessage ?: status,
                    onOpenAlbum = ::openAlbum,
                    onOpenPlaylist = { push(Route.Playlist(it.id)) },
                    onOpenArtist = ::openArtist,
                    onPlayTracks = { list, i -> session.player.play(list, i) },
                    onEnqueue = { session.player.enqueueAll(it) },
                    onUnpin = { kind, id -> session.collection.unpin(kind, id) },
                    onAddToPlaylist = { addToPlaylist = it },
                    playlistQuickAdd = { songs -> buildContextMenu(playlistAddAlternate(songs)) }
                )
                Route.Search -> DesktopExplore(
                    session = session,
                    query = query,
                    tracks = tracks,
                    albums = albums,
                    playlists = playlists,
                    onOpenAlbum = ::openAlbum,
                    onOpenPlaylist = { push(Route.Playlist(it.id)) },
                    onOpenArtist = ::openArtist,
                    onPlaySongs = { list, i -> session.player.play(list, i) },
                    likedIds = liked,
                    onToggleLike = session.collection::toggleLike,
                    songMenu = { songMenu(it) }
                )
                is Route.Album -> {
                    val live = albums.firstOrNull { it.id == r.album.id } ?: r.album
                    fun resolveRow(row: capital.yuri.yuriplayer.components.model.TrackRowModel): Track? {
                        val group = tracks.filter { it.id in row.sourceIds.ifEmpty { listOf(row.id) } }
                        if (group.isEmpty()) return tracks.firstOrNull { it.id == row.id }
                        val identity = albumPageIdentity(group.first())
                        return pickPreferred(group, session.sourcePrefs.get(identity))
                    }
                    val albumTracks = live.tracks.mapNotNull(::resolveRow)
                    AlbumPage(
                        album = live.copy(
                            tracks = live.tracks.map {
                                it.copy(highlighted = current?.id == it.id || current?.id in it.sourceIds)
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
                        },
                        onArtist = ::openArtist,
                        liked = session.collection.isSaved(DesktopCollection.Kind.ALBUM, live.id),
                        onToggleLike = {
                            session.collection.toggleSaved(
                                DesktopCollection.Pin(
                                    DesktopCollection.Kind.ALBUM,
                                    live.id,
                                    live.title,
                                    live.artist
                                )
                            )
                        },
                        likedTrackIds = liked,
                        onToggleTrackLike = session.collection::toggleLike,
                        songMenu = { row ->
                            tracks.firstOrNull { it.id == row.id }?.let { songMenu(it, onAlbumPage = true) }.orEmpty()
                        },
                        onSources = { row ->
                            val ids = row.sourceIds.ifEmpty { listOf(row.id) }.toHashSet()
                            sourcesFor = tracks.filter { it.id in ids }
                                .ifEmpty { tracks.filter { it.catalogKey() == row.id } }
                                .ifEmpty { listOfNotNull(tracks.firstOrNull { it.id == row.id }) }
                        }
                    )
                }
                is Route.Artist -> {
                    var profile by remember(r.name) { mutableStateOf<ArtistProfile?>(null) }
                    var pickBanner by remember { mutableStateOf(false) }
                    var cropBanner by remember { mutableStateOf<java.io.File?>(null) }
                    LaunchedEffect(r.name) {
                        profile = runCatching { session.artists.resolve(r.name) }.getOrNull()
                    }
                    val model = remember(r.name, tracks, liked, history, profile) {
                        val base = tracks.artistPage(r.name, liked, history)
                        base.copy(
                            about = profile?.bio ?: base.about,
                            artworkUri = profile?.imageUri ?: base.artworkUri,
                            bannerUri = when {
                                profile?.bannerCleared == true -> ""
                                !profile?.bannerUri.isNullOrBlank() -> profile!!.bannerUri
                                else -> null
                            },
                            genres = profile?.genres.orEmpty()
                        )
                    }
                    val artistTracks = remember(r.name, tracks) {
                        tracks.filter {
                            it.displayArtist.equals(r.name, true) ||
                                it.albumArtist.equals(r.name, true)
                        }
                    }
                    ArtistPage(
                        artist = model,
                        playing = playing && artistTracks.any { it.id == current?.id },
                        onPlay = {
                            if (artistTracks.isNotEmpty()) session.player.play(artistTracks, 0)
                        },
                        onShuffle = {
                            if (artistTracks.isNotEmpty()) {
                                if (!shuffle) session.player.toggleShuffle()
                                session.player.play(artistTracks, 0)
                            }
                        },
                        onTrack = { i ->
                            val popularIds = model.popular.map { it.id }
                            val list = popularIds.mapNotNull { id -> artistTracks.firstOrNull { it.id == id } }
                                .ifEmpty { artistTracks }
                            if (i in list.indices) session.player.play(list, i)
                        },
                        onOpenAlbum = ::openAlbum,
                        onChangeHeader = {
                            DesktopFiles.pickImage("Choose header")?.let { cropBanner = it }
                        },
                        onFetchHeader = { pickBanner = true },
                        onClearHeader = {
                            profile = session.artists.clearBanner(r.name)
                        },
                        liked = session.collection.isSaved(DesktopCollection.Kind.ARTIST, r.name),
                        onToggleLike = {
                            session.collection.toggleSaved(
                                DesktopCollection.Pin(
                                    DesktopCollection.Kind.ARTIST,
                                    r.name,
                                    r.name,
                                    "Artist"
                                )
                            )
                        },
                        likedTrackIds = liked,
                        onToggleTrackLike = session.collection::toggleLike,
                        songMenu = { row ->
                            tracks.firstOrNull { it.id == row.id }?.let { songMenu(it) }.orEmpty()
                        }
                    )
                    if (pickBanner) {
                        ArtistBannerPicker(
                            artistName = r.name,
                            client = session.artists,
                            onDismiss = { pickBanner = false },
                            onPicked = { c ->
                                pickBanner = false
                                scope.launch {
                                    cropBanner = DesktopFiles.downloadImage(c.url)
                                }
                            }
                        )
                    }
                    cropBanner?.let { src ->
                        ImageCropDialog(
                            source = src,
                            title = "Crop header",
                            aspect = 3f,
                            onCancel = { cropBanner = null },
                            onCropped = { cropped ->
                                runCatching {
                                    profile = session.artists.applyLocalBanner(r.name, cropped)
                                }
                                cropBanner = null
                            }
                        )
                    }
                }
                is Route.Playlist -> {
                    val pl = playlists.firstOrNull { it.id == r.id }
                    if (pl == null) {
                        Text("Playlist gone.", modifier = Modifier.padding(24.dp))
                    } else {
                        LaunchedEffect(pl.id, pl.snapshots.map { it.id }) {
                            session.ensureTracks(pl.snapshots)
                        }
                        PlaylistPage(
                            playlist = pl,
                            tracks = pl.tracks(tracks),
                            library = tracks,
                            currentId = current?.id,
                            store = session.playlists,
                            onBack = ::goBack,
                            onPlay = { list, i -> session.player.play(list, i) },
                            onEditTrack = { editSong = it },
                            likedIds = liked,
                            onToggleLike = session.collection::toggleLike,
                            playlistLiked = session.collection.isSaved(DesktopCollection.Kind.PLAYLIST, pl.id),
                            onTogglePlaylistLike = {
                                session.collection.toggleSaved(
                                    DesktopCollection.Pin(
                                        DesktopCollection.Kind.PLAYLIST,
                                        pl.id,
                                        pl.name,
                                        "Playlist"
                                    )
                                )
                            },
                            songMenu = { songMenu(it, playlistId = pl.id) }
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
        if (showNewPlaylist) {
            NewPlaylistDialog(
                onDismiss = { showNewPlaylist = false },
                onCreate = { name, description ->
                    val created = session.playlists.create(name, description)
                    showNewPlaylist = false
                    push(Route.Playlist(created.id))
                }
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
        addToPlaylist?.let { songs ->
            AddToPlaylistDialog(
                tracks = songs,
                store = session.playlists,
                library = tracks,
                onDismiss = { addToPlaylist = null },
                onEnsureTracks = { session.ensureTracks(it) }
            )
        }
        sourcesFor?.let { group ->
            val preferredId = group.firstOrNull()?.let { session.sourcePrefs.get(albumPageIdentity(it)) }
                ?: group.minByOrNull { it.sourceRank() }?.id
            SourcesPickerDialog(
                title = group.first().displayTitle,
                choices = sourceChoices(group, remotes, preferredId),
                onDismiss = { sourcesFor = null },
                onPick = { picked ->
                    session.sourcePrefs.set(albumPageIdentity(picked), picked.id)
                    prefRev++
                    sourcesFor = null
                }
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
    pins: List<DesktopCollection.Pin>,
    liked: Set<String>,
    status: String,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onEnqueue: (List<Track>) -> Unit = {},
    onUnpin: (DesktopCollection.Kind, String) -> Unit = { _, _ -> },
    onAddToPlaylist: (List<Track>) -> Unit = {},
    playlistQuickAdd: (List<Track>) -> List<MenuEntry> = { emptyList() }
) {
    val recentAlbums = remember(recents, albums) {
        recents.mapNotNull { t ->
            albums.firstOrNull { a -> a.tracks.any { it.id == t.id } }
        }.distinctBy { it.id }
    }
    val likedTracks = remember(liked, tracks) { tracks.filter { it.id in liked } }
    val shortcuts = remember(pins, liked, likedTracks, albums, playlists, tracks) {
        buildHomeShortcuts(
            pins, liked, likedTracks, albums, playlists, tracks,
            onOpenAlbum, onOpenPlaylist, onOpenArtist, onPlayTracks,
            onEnqueue, onUnpin, onAddToPlaylist, playlistQuickAdd
        )
    }
    val greeting = remember {
        val h = java.time.LocalTime.now().hour
        when {
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val stuffAlbums = remember(pins, liked, albums) {
        val pinnedAlbumIds = pins.filter { it.kind == DesktopCollection.Kind.ALBUM }.map { it.id }.toSet()
        val pinnedTitles = pins.filter { it.kind == DesktopCollection.Kind.ALBUM }
            .map { it.title.lowercase() }.toSet()
        albums.filter { album ->
            album.id in pinnedAlbumIds ||
                album.title.lowercase() in pinnedTitles ||
                album.tracks.any { it.id in liked }
        }
    }
    val stuffArtists = remember(pins, liked, albums, tracks) {
        val pinned = pins.filter { it.kind == DesktopCollection.Kind.ARTIST }.map { it.title }
        val fromAlbums = stuffAlbums.map { it.artist }
        val fromLiked = tracks.filter { it.id in liked }.map { it.displayArtist }
        (pinned + fromAlbums + fromLiked).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            greeting,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("All") })
            FilterChip(selected = false, onClick = {}, label = { Text("Music") })
        }
        if (shortcuts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            shortcuts.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { pin ->
                        SpotifyPinCard(pin = pin, modifier = Modifier.weight(1f))
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (recents.isNotEmpty()) {
            SectionHeader("Jump back in")
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
                        onClick = { onPlayTracks(listOf(track) + tracks, 0) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (playlists.isNotEmpty()) {
            SectionHeader("Your playlists")
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
        if (recentAlbums.isNotEmpty()) {
            SectionHeader("Recently played")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recentAlbums.take(12), key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (stuffAlbums.isNotEmpty()) {
            val mixAlbums = remember(stuffAlbums) { stuffAlbums.shuffled().take(12) }
            SectionHeader("Albums from My Stuff")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(mixAlbums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (stuffArtists.isNotEmpty()) {
            SectionHeader("Artists from My Stuff")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(stuffArtists.take(12), key = { it }) { name ->
                    val cover = albums.firstOrNull { it.artist == name }?.artworkUri
                    Column(
                        Modifier.width(140.dp).clickable { onOpenArtist(name) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CoverArt(
                            model = cover,
                            size = 128.dp,
                            corner = 64.dp,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
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
            Spacer(Modifier.height(24.dp))
        }
        if (shortcuts.isEmpty() && stuffAlbums.isEmpty() && playlists.isEmpty() && recents.isEmpty()) {
            Text(
                "Pin albums, artists, or playlists from My Stuff and they’ll show up here.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 24.dp)
            )
        }
        if (albums.isEmpty() && playlists.isEmpty() && recents.isEmpty() && tracks.isEmpty()) {
            Text(
                status.ifBlank { "Nothing in the library yet." },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private data class SpotifyPin(
    val id: String,
    val title: String,
    val artworkUri: String?,
    val circular: Boolean = false,
    val onClick: () -> Unit,
    val menu: List<out MenuEntry> = emptyList()
)

private fun buildHomeShortcuts(
    pins: List<DesktopCollection.Pin>,
    liked: Set<String>,
    likedTracks: List<Track>,
    albums: List<AlbumPageModel>,
    playlists: List<capital.yuri.yuriplayer.desktop.DesktopPlaylist>,
    tracks: List<Track>,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onEnqueue: (List<Track>) -> Unit,
    onUnpin: (DesktopCollection.Kind, String) -> Unit,
    onAddToPlaylist: (List<Track>) -> Unit,
    playlistQuickAdd: (List<Track>) -> List<MenuEntry>
): List<SpotifyPin> {
    val out = ArrayList<SpotifyPin>(8)
    fun add(pin: SpotifyPin) {
        if (out.size >= 8) return
        if (out.any { it.id == pin.id }) return
        out += pin
    }
    fun menu(
        kind: DesktopCollection.Kind?,
        id: String,
        playable: List<Track>,
        play: () -> Unit
    ) = buildList {
        if (playable.isNotEmpty() || kind == null) {
            add(ContextAction("Play", onClick = play))
        }
        if (playable.isNotEmpty()) {
            add(ContextAction("Add to queue") { onEnqueue(playable) })
        }
        if (playable.isNotEmpty()) {
            add(
                ContextAction(
                    label = "Add to playlist",
                    alternate = playlistQuickAdd(playable),
                    onClick = { onAddToPlaylist(playable) }
                )
            )
        }
        if (kind != null) {
            add(ContextAction("Unpin", destructive = true) { onUnpin(kind, id) })
        }
    }
    if (liked.isNotEmpty()) {
        add(
            SpotifyPin(
                id = "liked",
                title = "Liked Songs",
                artworkUri = likedTracks.firstOrNull()?.artworkUri,
                onClick = { onPlayTracks(likedTracks, 0) },
                menu = menu(null, "liked", likedTracks) { onPlayTracks(likedTracks, 0) }
            )
        )
    }
    for (pin in pins) {
        when (pin.kind) {
            DesktopCollection.Kind.ALBUM -> {
                val album = albums.firstOrNull { it.id == pin.id }
                    ?: albums.firstOrNull { it.title.equals(pin.title, true) }
                val playable = album?.tracks?.mapNotNull { row -> tracks.firstOrNull { it.id == row.id } }.orEmpty()
                add(
                    SpotifyPin(
                        pin.id, pin.title, album?.artworkUri,
                        onClick = { album?.let(onOpenAlbum) },
                        menu = menu(DesktopCollection.Kind.ALBUM, pin.id, playable) {
                            if (playable.isNotEmpty()) onPlayTracks(playable, 0)
                            else album?.let(onOpenAlbum)
                        }
                    )
                )
            }
            DesktopCollection.Kind.ARTIST -> {
                val playable = tracks.filter {
                    it.displayArtist.equals(pin.title, true) || it.albumArtist.equals(pin.title, true)
                }
                add(
                    SpotifyPin(
                        pin.id,
                        pin.title,
                        tracks.firstOrNull { it.displayArtist.equals(pin.title, true) }?.artworkUri,
                        circular = true,
                        onClick = { onOpenArtist(pin.title) },
                        menu = menu(DesktopCollection.Kind.ARTIST, pin.id, playable) { onOpenArtist(pin.title) }
                    )
                )
            }
            DesktopCollection.Kind.SONG -> {
                val t = tracks.firstOrNull { it.id == pin.id }
                val playable = listOfNotNull(t)
                add(
                    SpotifyPin(
                        pin.id, pin.title, t?.artworkUri,
                        onClick = { t?.let { onPlayTracks(listOf(it), 0) } },
                        menu = menu(DesktopCollection.Kind.SONG, pin.id, playable) {
                            t?.let { onPlayTracks(listOf(it), 0) }
                        }
                    )
                )
            }
            DesktopCollection.Kind.PLAYLIST -> {
                val pl = playlists.firstOrNull { it.id == pin.id }
                val playable = pl?.tracks(tracks).orEmpty()
                add(
                    SpotifyPin(
                        pin.id, pin.title, pl?.artworkUri(tracks),
                        onClick = { pl?.let(onOpenPlaylist) },
                        menu = menu(DesktopCollection.Kind.PLAYLIST, pin.id, playable) {
                            if (playable.isNotEmpty()) onPlayTracks(playable, 0)
                            else pl?.let(onOpenPlaylist)
                        }
                    )
                )
            }
        }
    }
    return out
}

@Composable
private fun SpotifyPinCard(pin: SpotifyPin, modifier: Modifier = Modifier) {
    ContextMenuAnchor(items = pin.menu, modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = pin.onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                model = pin.artworkUri,
                size = 72.dp,
                corner = if (pin.circular) 36.dp else 0.dp,
                contentScale = if (pin.circular) {
                    androidx.compose.ui.layout.ContentScale.Crop
                } else {
                    androidx.compose.ui.layout.ContentScale.Fit
                }
            )
            Text(
                pin.title,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
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
