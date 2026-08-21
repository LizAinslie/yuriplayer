package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.HomeRowId
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.player.PlaybackHistoryStore
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.ui.TestTags
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun HomeFeedScreen(
    library: LibraryIndex,
    onPlay: (List<Song>, Int) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit
) {
    val pinStore: MyStuffPinStore = koinInject()
    val playlistsRepo: PlaylistRepository = koinInject()
    val catalog: CatalogRepository = koinInject()
    val player: PlayerController = koinInject()
    val history: PlaybackHistoryStore = koinInject()
    val settings: LibrarySettings = koinInject()
    val pins by pinStore.pins.collectAsState()
    val entries by pinStore.entries.collectAsState()
    val playlists by playlistsRepo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val allSongs by library.songs.collectAsState()
    val historyEntries by history.entries.collectAsState()
    val colorRev by settings.colorPrefsRevision.collectAsState()
    val rows = remember(colorRev) { settings.enabledHomeRows() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddPin by remember { mutableStateOf(false) }

    LaunchedEffect(playlistsRepo) {
        playlistsRepo.observePlaylistsResolved().collect { list ->
            pinStore.pruneMissingPlaylists(list.map { it.id }.toSet())
        }
    }

    val stuffAlbums = remember(entries, allSongs) {
        library.albums(taggedOnly = false).filter { album ->
            entries.any { it.kind == StuffPinKind.ALBUM && it.title.equals(album.name, true) } ||
                album.songs.any { song -> entries.any { it.kind == StuffPinKind.SONG && it.id == song.songKey } }
        }.ifEmpty { library.albums(taggedOnly = false) }
    }
    val stuffArtists = remember(entries) {
        library.artists(taggedOnly = false).filter { artist ->
            entries.any { it.kind == StuffPinKind.ARTIST && it.title.equals(artist.name, true) }
        }.ifEmpty { library.artists(taggedOnly = false) }
    }

    LazyColumn(Modifier.fillMaxSize().testTag(TestTags.MYSTUFF_PINS)) {
        item {
            MyStuffPinsHost(
                pins = pins,
                library = library,
                playlists = playlists,
                allSongs = allSongs,
                onOpenPin = { pin ->
                    scope.launch {
                        openPin(
                            pin, library, playlists, allSongs, catalog,
                            onOpenAlbum, onOpenArtist, onOpenPlaylist, onOpenSongAlbum, context
                        )
                    }
                },
                onUnpin = { pinStore.unpin(it) },
                onAddPinSlot = { showAddPin = true },
                onPlayAll = {
                    scope.launch {
                        val songs = resolveCollectionSongs(entries, library, playlistsRepo)
                        if (songs.isEmpty()) {
                            Toast.makeText(context, "Nothing in My Stuff yet", Toast.LENGTH_SHORT).show()
                        } else {
                            player.startPlaylistRadio(songs, "My Stuff")
                        }
                    }
                }
            )
        }
        if (HomeRowId.RECENTS in rows && historyEntries.isNotEmpty()) {
            item {
                HomeRow(title = "Recents") {
                    historyEntries.take(16).forEach { entry ->
                        CoverTile(
                            title = entry.song.displayTitle,
                            subtitle = entry.song.displayArtist,
                            onClick = { onOpenSongAlbum(entry.song) },
                            art = { AlbumArt(song = entry.song, size = 112.dp, corner = 8.dp) }
                        )
                    }
                }
            }
        }
        if (HomeRowId.RANDOM_ALBUMS in rows && stuffAlbums.isNotEmpty()) {
            item {
                HomeRow(title = "Random albums") {
                    stuffAlbums.shuffled().take(10).forEach { album ->
                        CoverTile(
                            title = album.displayName,
                            subtitle = album.displayArtist,
                            onClick = { onOpenAlbum(album) },
                            art = { AlbumArt(song = album.songs.firstOrNull(), size = 112.dp, corner = 8.dp) }
                        )
                    }
                }
            }
        }
        if (HomeRowId.RANDOM_ARTISTS in rows && stuffArtists.isNotEmpty()) {
            item {
                HomeRow(title = "Random artists") {
                    stuffArtists.shuffled().take(10).forEach { artist ->
                        CoverTile(
                            title = artist.displayName,
                            subtitle = "${artist.albumCount} albums",
                            onClick = { onOpenArtist(artist) },
                            art = { ArtistArt(artistName = artist.displayName, size = 112.dp, circular = true) }
                        )
                    }
                }
            }
        }
        if (HomeRowId.RANDOM_PLAYLISTS in rows && playlists.isNotEmpty()) {
            item {
                HomeRow(title = "Random playlists") {
                    playlists.shuffled().take(10).forEach { pl ->
                        CoverTile(
                            title = pl.name,
                            subtitle = "Playlist",
                            onClick = { onOpenPlaylist(pl) },
                            art = { AlbumArt(song = null, size = 112.dp, corner = 8.dp) }
                        )
                    }
                }
            }
        }
        if (HomeRowId.RANDOM_SONGS in rows && allSongs.isNotEmpty()) {
            item {
                HomeRow(title = "Random songs") {
                    allSongs.shuffled().take(12).forEach { song ->
                        CoverTile(
                            title = song.displayTitle,
                            subtitle = song.displayArtist,
                            onClick = { onPlay(listOf(song) + allSongs.shuffled().take(20), 0) },
                            art = { AlbumArt(song = song, size = 112.dp, corner = 8.dp) }
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAddPin) {
        AddPinFromCollectionSheet(
            entries = entries,
            playlists = playlists,
            alreadyPinned = pins.map { it.key }.toSet(),
            library = library,
            allSongs = allSongs,
            onDismiss = { showAddPin = false },
            onPick = { pin ->
                pinStore.pin(pin)
                showAddPin = false
            }
        )
    }
}

@Composable
private fun HomeRow(
    title: String,
    content: @Composable RowScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun CoverTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit
) {
    Column(
        Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        art()
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}
