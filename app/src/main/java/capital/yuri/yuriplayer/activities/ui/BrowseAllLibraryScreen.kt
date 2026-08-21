package capital.yuri.yuriplayer.activities.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import org.koin.compose.koinInject

@Composable
fun BrowseAllLibraryScreen(
    library: LibraryIndex,
    nowPlaying: Song?,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAlbum: (AlbumItem) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSongAlbum: (Song) -> Unit
) {
    BackHandler(enabled = LocalTabBackEnabled.current, onBack = onBack)
    val playlistsRepo: PlaylistRepository = koinInject()
    val songs by library.songs.collectAsState()
    val playlists by playlistsRepo.observePlaylistsResolved().collectAsState(initial = emptyList())
    val albums = remember(songs) { library.albums(taggedOnly = false) }
    val artists = remember(songs) { library.artists(taggedOnly = false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsTopBar(title = "All", onBack = onBack)
        MediaBrowser(
            lookup = mediaBrowserLookup(
                songs = songs,
                albums = albums,
                artists = artists,
                playlists = playlists
            ),
            nowPlaying = nowPlaying,
            onPlay = onPlay,
            onAddToQueue = onAddToQueue,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onOpenPlaylist = onOpenPlaylist,
            onSongClick = onOpenSongAlbum
        )
    }
}
