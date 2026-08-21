package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.album.AlbumPage
import capital.yuri.yuriplayer.components.layout.DesktopNav
import capital.yuri.yuriplayer.components.layout.SpotifyShell
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
import capital.yuri.yuriplayer.desktop.DesktopSession

private sealed class Route {
    data object Library : Route()
    data class Album(val album: AlbumPageModel) : Route()
}

@Composable
fun YuriDesktopApp(session: DesktopSession) {
    val coverPixels by session.coverPixels.collectAsState()
    val playerColors = remember(coverPixels) {
        coverPixels?.let { playerColorsFromPixels(it) }
    }
    YuriTheme {
        val tracks by session.tracks.collectAsState()
        val current by session.player.current.collectAsState()
        val playing by session.player.isPlaying.collectAsState()
        val queue by session.player.queue.collectAsState()
        val history by session.player.history.collectAsState()
        val status by session.scanMessage.collectAsState()
        val position by session.positionMs.collectAsState()
        val duration by session.durationMs.collectAsState()
        val engineMessage by session.engineMessage.collectAsState()
        val albums = remember(tracks) { tracks.albums() }
        var route by remember { mutableStateOf<Route>(Route.Library) }
        var nav by remember { mutableStateOf(DesktopNav.Home) }
        var showSettings by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }

        SpotifyShell(
            nav = nav,
            onNav = {
                nav = it
                route = Route.Library
            },
            onSettings = { showSettings = true },
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
                    accent = playerColors?.accent ?: MaterialTheme.colorScheme.primary
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
                        }
                    )
                }
            }
        ) {
            when (val r = route) {
                Route.Library -> LibraryGrid(
                    albums = albums.filter {
                        nav != DesktopNav.Search || query.isBlank() ||
                            it.title.contains(query, true) ||
                            it.artist.contains(query, true)
                    },
                    status = engineMessage ?: status,
                    roots = session.dirs.defaultMusicRoots,
                    showSearch = nav == DesktopNav.Search,
                    query = query,
                    onQuery = { query = it },
                    onOpen = { route = Route.Album(it) }
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
                        onBack = { route = Route.Library },
                        onPlay = {
                            if (albumTracks.isNotEmpty()) session.player.play(albumTracks, 0)
                        },
                        onTrack = { i ->
                            if (i in albumTracks.indices) session.player.play(albumTracks, i)
                        }
                    )
                }
            }
        }
        if (showSettings) {
            DesktopSettingsDialog(onDismiss = { showSettings = false })
        }
    }
}

@Composable
private fun LibraryGrid(
    albums: List<AlbumPageModel>,
    status: String,
    roots: List<String>,
    showSearch: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (AlbumPageModel) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            if (showSearch) "Search" else "Library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                placeholder = { Text("Albums and artists") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
        } else {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)
            )
        }
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (showSearch) "Nothing matches."
                    else "Nothing in the default music folders yet.\n" +
                        roots.joinToString("\n").ifBlank { "Add files to Music, then restart." },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
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
