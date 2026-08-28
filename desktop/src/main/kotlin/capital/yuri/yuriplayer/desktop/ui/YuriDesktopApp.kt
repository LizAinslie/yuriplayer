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
import capital.yuri.yuriplayer.core.library.albumPageIdentity
import capital.yuri.yuriplayer.core.library.catalogKey
import capital.yuri.yuriplayer.core.library.pickPreferred
import capital.yuri.yuriplayer.core.library.sourceRank
import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.ColdSourceType
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.desktop.DesktopCollection
import capital.yuri.yuriplayer.desktop.DesktopSession
import kotlinx.coroutines.launch

private sealed class Route {
    data object Home : Route()
    data object Search : Route()
    data class Album(val album: AlbumPageModel) : Route()
    data class Playlist(val id: String) : Route()
    data class Artist(val name: String) : Route()
    data object MyStuff : Route()
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
        val online by session.network.isOnline.collectAsState()
        val shuffle by session.player.shuffle.collectAsState()
        val repeat by session.player.repeat.collectAsState()
        val volume by session.player.volume.collectAsState()
        val liked by session.collection.liked.collectAsState()
        val pins by session.collection.pinned.collectAsState()
        val saved by session.collection.saved.collectAsState()
        val hotQueue by session.player.hotQueue.collectAsState()
        val coldQueue by session.player.coldQueue.collectAsState()
        val coldSource by session.player.coldSource.collectAsState()
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
        var editSong by remember { mutableStateOf<Song?>(null) }
        var editAlbum by remember { mutableStateOf<AlbumPageModel?>(null) }
        var addToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
        var sourcesFor by remember { mutableStateOf<List<Song>?>(null) }
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

        fun playlistAddAlternate(songs: List<Song>): ContextMenuScope.() -> Unit = {
            val candidates = playlists
                .filter { pl ->
                    songs.any { t -> pl.orderedEntries().none { e -> e.matches(t) } }
                }
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
            track: Song,
            onAlbumPage: Boolean = false,
            playlistId: String? = null
        ): List<MenuEntry> = buildContextMenu {
            item("Add to playlist", alternate = playlistAddAlternate(listOf(track))) {
                addToPlaylist = listOf(track)
            }
            item("Add to queue") { session.player.enqueue(track) }
            item("Sources") {
                val ids = HashSet<String>()
                ids += track.songKey
                ids += track.catalogKey()
                val row = albums.asSequence()
                    .mapNotNull { a -> a.tracks.firstOrNull { track.songKey in it.sourceIds || it.id == track.songKey } }
                    .firstOrNull()
                if (row != null) ids += row.sourceIds
                val want = ids
                sourcesFor = tracks.asSequence()
                    .filter { it.songKey in want || it.catalogKey() in want }
                    .distinctBy { it.songKey }
                    .take(16)
                    .toList()
                    .ifEmpty { listOf(track) }
            }
            submenu("Go to") {
                if (!onAlbumPage) {
                    item("Album") {
                        albums.firstOrNull { a ->
                            a.tracks.any { track.songKey in it.sourceIds || it.id == track.songKey }
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

        fun menuForQueueRow(row: TrackRowModel, pool: List<Song>): List<MenuEntry> {
            val track = pool.firstOrNull { it.songKey == row.id } ?: Song(
                id = 0L,
                contentUri = "",
                title = row.title,
                artist = row.artist,
                album = row.album,
                durationMs = row.durationMs,
                albumArtUri = row.artworkUri
            )
            return songMenu(track)
        }

        fun pinTracks(item: LibraryRailItem): List<Song> = when {
            item.id == "liked" -> tracks.filter { it.songKey in liked }
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
                    val group = tracks.filter { it.songKey in row.sourceIds }
                    pickPreferred(group, session.sourcePrefs.get(albumPageIdentity(group.firstOrNull() ?: return@mapNotNull null)))
                }.orEmpty()
                    .ifEmpty { tracks.filter { it.songKey == item.id }.takeIf { it.isNotEmpty() } ?: emptyList() }
            }
        }

        val recency = remember(history) {
            history.mapIndexed { i, t -> t.songKey to i }.toMap()
        }
        fun albumRecency(album: AlbumPageModel): Int =
            album.tracks.minOfOrNull { recency[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE

        val libraryItems = remember(albums, tracks, libraryFilter, pins, saved, history, liked, playlists) {
            val likedAlbums = albums.filter { album -> album.tracks.any { it.id in liked } }
            val likedArtists = tracks.filter { it.songKey in liked }.map { it.displayArtist }.distinct()
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
                        tracks.firstOrNull { it.displayArtist.equals(pin.title, true) }?.albumArtUri,
                        circular = true, pinned = true
                    )
                    DesktopCollection.Kind.SONG -> {
                        val t = tracks.firstOrNull { it.songKey == pin.id }
                        LibraryRailItem(
                            pin.id, pin.title, pin.subtitle,
                            t?.albumArtUri, pinned = true
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
                        id = "liked",
                        title = "My Stuff",
                        subtitle = "${liked.size} songs",
                        artworkUri = null,
                        heart = true
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
                            tracks.firstOrNull { it.displayArtist.equals(name, true) }?.albumArtUri,
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
                    buildList {
                        history.forEach { t ->
                            val album = albums.firstOrNull { a -> a.tracks.any { it.id == t.songKey } }
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
                    item.id == "liked" -> push(Route.MyStuff)
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
            offline = !online,
            onLeftFraction = { f, persist -> session.layout.setLeft(f, persist) },
            onRightFraction = { f, persist -> session.layout.setRight(f, persist) },
            bottomBar = {
                PlayerChromeTheme(playerColors, useArtBackground = true) {
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
                        liked = current?.songKey in liked,
                        onToggleLike = {
                            val t = current ?: return@BottomPlayerBar
                            session.collection.toggleLike(t.songKey)
                        },
                        queueVisible = showSidebar,
                        onToggleQueue = { showSidebar = !showSidebar },
                        songMenu = current?.let { songMenu(it) }.orEmpty()
                    )
                }
            },
            sidebar = {
                PlayerChromeTheme(playerColors, useArtBackground = true) {
                    val curId = current?.songKey
                    val hotIdx = hotQueue.indexOfFirst { it.songKey == curId }
                    val coldIdx = coldQueue.indexOfFirst { it.songKey == curId }
                    val hotUp = if (hotIdx >= 0) hotQueue.drop(hotIdx + 1) else hotQueue
                    val coldUp = if (coldIdx >= 0) coldQueue.drop(coldIdx + 1) else coldQueue
                    val coldLabel = coldSource?.title?.takeIf { it.isNotBlank() }
                        ?: coldUp.firstOrNull()?.displayAlbum?.takeIf { it.isNotBlank() && it != "Unknown Album" }
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
                            val t = history.firstOrNull { it.songKey == row.id } ?: return@NowPlayingSidebar
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
                Route.MyStuff -> MyStuffPage(
                    tracks = tracks,
                    albums = albums,
                    playlists = playlists,
                    pins = pins,
                    liked = liked,
                    onOpenAlbum = ::openAlbum,
                    onOpenPlaylist = { push(Route.Playlist(it.id)) },
                    onOpenArtist = ::openArtist,
                    onPlayTracks = { list, i -> session.player.play(list, i) }
                )
                is Route.Album -> {
                    val live = albums.firstOrNull { it.id == r.album.id } ?: r.album
                    fun resolveRow(row: TrackRowModel): Song? {
                        val group = tracks.filter { it.songKey in row.sourceIds.ifEmpty { listOf(row.id) } }
                        if (group.isEmpty()) return tracks.firstOrNull { it.songKey == row.id }
                        val identity = albumPageIdentity(group.first())
                        return pickPreferred(group, session.sourcePrefs.get(identity))
                    }
                    val albumTracks = live.tracks.mapNotNull(::resolveRow)
                    AlbumPage(
                        album = live.copy(
                            tracks = live.tracks.map {
                                it.copy(highlighted = current?.songKey == it.id || current?.songKey in it.sourceIds)
                            }
                        ),
                        playing = playing && session.player.isPlayingFromAlbum(live.id),
                        onBack = ::goBack,
                        onPlay = {
                            if (albumTracks.isNotEmpty()) session.player.play(albumTracks, 0, ColdSource(ColdSourceType.ALBUM, live.id, live.title))
                        },
                        onTrack = { i ->
                            if (i in albumTracks.indices) session.player.play(albumTracks, i, ColdSource(ColdSourceType.ALBUM, live.id, live.title))
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
                            tracks.firstOrNull { it.songKey == row.id }?.let { songMenu(it, onAlbumPage = true) }.orEmpty()
                        },
                        onSources = { row ->
                            val ids = row.sourceIds.ifEmpty { listOf(row.id) }.toHashSet()
                            sourcesFor = tracks.filter { it.songKey in ids }
                                .ifEmpty { tracks.filter { it.catalogKey() == row.id } }
                                .ifEmpty { listOfNotNull(tracks.firstOrNull { it.songKey == row.id }) }
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
                        playing = playing && session.player.isPlayingFromArtist(r.name),
                        onPlay = {
                            if (artistTracks.isNotEmpty()) session.player.play(artistTracks, 0, ColdSource(ColdSourceType.ARTIST, r.name, r.name))
                        },
                        onShuffle = {
                            if (artistTracks.isNotEmpty()) {
                                if (!shuffle) session.player.toggleShuffle()
                                session.player.play(artistTracks, 0, ColdSource(ColdSourceType.ARTIST, r.name, r.name))
                            }
                        },
                        onTrack = { i ->
                            val popularIds = model.popular.map { it.id }
                            val list = popularIds.mapNotNull { id -> artistTracks.firstOrNull { it.songKey == id } }
                                .ifEmpty { artistTracks }
                            if (i in list.indices) session.player.play(list, i, ColdSource(ColdSourceType.ARTIST, r.name, r.name))
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
                            tracks.firstOrNull { it.songKey == row.id }?.let { songMenu(it) }.orEmpty()
                        }
                    )
                    if (pickBanner) {
                        ArtistBannerPicker(
                            artistName = r.name,
                            client = session.artists,
                            onDismiss = { pickBanner = false },
                            onPicked = { candidate ->
                                scope.launch {
                                    runCatching {
                                        profile = session.artists.applyBannerUrl(r.name, candidate.url)
                                    }
                                    pickBanner = false
                                }
                            }
                        )
                    }
                    if (cropBanner != null) {
                        ImageCropDialog(
                            source = cropBanner!!,
                            title = "Crop banner",
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
                        LaunchedEffect(pl.id, pl.snapshots.map { it.songKey }) {
                            session.ensureTracks(pl.snapshots)
                        }
                        PlaylistPage(
                            playlist = pl,
                            tracks = pl.tracks(tracks),
                            library = tracks,
                            currentId = current?.songKey,
                            playing = playing && session.player.isPlayingFromPlaylist(pl.id),
                            store = session.playlists,
                            onBack = ::goBack,
                            onPlay = { list, i -> session.player.play(list, i, ColdSource(ColdSourceType.PLAYLIST, pl.id, pl.name)) },
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
            val albumTracks = tracks.filter { t -> album.tracks.any { it.id == t.songKey } }
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
                ?: group.minByOrNull { it.sourceRank() }?.songKey
            SourcesPickerDialog(
                title = group.first().displayTitle,
                choices = sourceChoices(group, remotes, preferredId),
                onDismiss = { sourcesFor = null },
                onPick = { picked ->
                    session.sourcePrefs.set(albumPageIdentity(picked), picked.songKey)
                    prefRev++
                    sourcesFor = null
                }
            )
        }
    }
}

@Composable
private fun MyStuffPage(
    tracks: List<Song>,
    albums: List<AlbumPageModel>,
    playlists: List<capital.yuri.yuriplayer.desktop.DesktopPlaylist>,
    pins: List<DesktopCollection.Pin>,
    liked: Set<String>,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Song>, Int) -> Unit
) {
    val likedTracks = remember(liked, tracks) { tracks.filter { it.songKey in liked } }
    val pinnedAlbumIds = remember(pins) {
        pins.filter { it.kind == DesktopCollection.Kind.ALBUM }.map { it.id }.toSet()
    }
    val pinnedAlbumTitles = remember(pins) {
        pins.filter { it.kind == DesktopCollection.Kind.ALBUM }.map { it.title.lowercase() }.toSet()
    }
    val pinnedAlbums = remember(albums, pinnedAlbumIds, pinnedAlbumTitles) {
        albums.filter { it.id in pinnedAlbumIds || it.title.lowercase() in pinnedAlbumTitles }
    }
    val pinnedArtists = remember(pins, tracks) {
        pins.filter { it.kind == DesktopCollection.Kind.ARTIST }.map { p ->
            p.title to tracks.firstOrNull {
                it.displayArtist.equals(p.title, true) || it.albumArtist.equals(p.title, true)
            }?.albumArtUri
        }
    }
    val pinnedPlaylists = remember(pins, playlists) {
        val ids = pins.filter { it.kind == DesktopCollection.Kind.PLAYLIST }.map { it.id }.toSet()
        playlists.filter { it.id in ids }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            "My Stuff",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))

        if (likedTracks.isNotEmpty()) {
            SectionHeader("Liked songs")
            Column {
                likedTracks.forEach { track ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPlayTracks(likedTracks, likedTracks.indexOf(track)) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverArt(model = track.albumArtUri, size = 44.dp, corner = 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.displayTitle,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                track.displayArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (pinnedAlbums.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Albums")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pinnedAlbums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                }
            }
        }

        if (pinnedArtists.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Artists")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pinnedArtists, key = { it.first }) { (name, cover) ->
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
        }

        if (pinnedPlaylists.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Playlists")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pinnedPlaylists, key = { it.id }) { pl ->
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
        }

        if (likedTracks.isEmpty() && pinnedAlbums.isEmpty() && pinnedArtists.isEmpty() && pinnedPlaylists.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Nothing here yet — like songs or pin albums, artists, and playlists.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun HomeFeed(
    albums: List<AlbumPageModel>,
    playlists: List<capital.yuri.yuriplayer.desktop.DesktopPlaylist>,
    tracks: List<Song>,
    recents: List<Song>,
    pins: List<DesktopCollection.Pin>,
    liked: Set<String>,
    status: String,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Song>, Int) -> Unit,
    onEnqueue: (List<Song>) -> Unit = {},
    onUnpin: (DesktopCollection.Kind, String) -> Unit = { _, _ -> },
    onAddToPlaylist: (List<Song>) -> Unit = {},
    playlistQuickAdd: (List<Song>) -> List<MenuEntry> = { emptyList() }
) {
    val recentAlbums = remember(recents, albums) {
        recents.mapNotNull { t ->
            albums.firstOrNull { a -> a.tracks.any { it.id == t.songKey } }
        }.distinctBy { it.id }
    }
    val likedTracks = remember(liked, tracks) { tracks.filter { it.songKey in liked } }
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
        val fromLiked = tracks.filter { it.songKey in liked }.map { it.displayArtist }
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
                items(recents.take(12), key = { it.songKey }) { track ->
                    AlbumCard(
                        album = AlbumPageModel(
                            id = track.songKey,
                            title = track.displayTitle,
                            artist = track.displayArtist,
                            artworkUri = track.albumArtUri,
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
    likedTracks: List<Song>,
    albums: List<AlbumPageModel>,
    playlists: List<capital.yuri.yuriplayer.desktop.DesktopPlaylist>,
    tracks: List<Song>,
    onOpenAlbum: (AlbumPageModel) -> Unit,
    onOpenPlaylist: (capital.yuri.yuriplayer.desktop.DesktopPlaylist) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Song>, Int) -> Unit,
    onEnqueue: (List<Song>) -> Unit,
    onUnpin: (DesktopCollection.Kind, String) -> Unit,
    onAddToPlaylist: (List<Song>) -> Unit,
    playlistQuickAdd: (List<Song>) -> List<MenuEntry>
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
        playable: List<Song>,
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
                title = "My Stuff",
                artworkUri = likedTracks.firstOrNull()?.albumArtUri,
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
                val playable = album?.tracks?.mapNotNull { row -> tracks.firstOrNull { it.songKey == row.id } }.orEmpty()
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
                        tracks.firstOrNull { it.displayArtist.equals(pin.title, true) }?.albumArtUri,
                        circular = true,
                        onClick = { onOpenArtist(pin.title) },
                        menu = menu(DesktopCollection.Kind.ARTIST, pin.id, playable) { onOpenArtist(pin.title) }
                    )
                )
            }
            DesktopCollection.Kind.SONG -> {
                val t = tracks.firstOrNull { it.songKey == pin.id }
                val playable = listOfNotNull(t)
                add(
                    SpotifyPin(
                        pin.id, pin.title, t?.albumArtUri,
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
                columns = GridCells.Adaptive(180.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onOpen(album) })
                }
            }
        }
    }
}
