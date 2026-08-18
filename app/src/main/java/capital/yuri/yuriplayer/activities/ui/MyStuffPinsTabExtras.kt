package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.StuffPin
import capital.yuri.yuriplayer.data.StuffPinKind
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import org.koin.compose.koinInject

/**
 * Long-press on a pin: kind-specific actions from [LocalAlbumNav] /
 * [LocalArtistNav] / [LocalPlaylistNav] / [LocalSongNav], plus reorder + unpin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinActionSheetWithReorder(
    pin: StuffPin,
    onDismiss: () -> Unit,
    onReorder: () -> Unit,
    onUnpin: () -> Unit
) {
    val context = LocalContext.current
    val library: LibraryIndex = koinInject()
    val playlistRepo: PlaylistRepository = koinInject()
    val songNav = LocalSongNav.current
    val albumNav = LocalAlbumNav.current
    val artistNav = LocalArtistNav.current
    val playlistNav = LocalPlaylistNav.current

    val allSongs by library.songs.collectAsState()
    val playlists by playlistRepo.observePlaylistsResolved().collectAsState(initial = emptyList())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                pin.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (pin.subtitle.isNotBlank()) {
                Text(
                    pin.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            when (pin.kind) {
                StuffPinKind.SONG -> {
                    val song = remember(pin.id, allSongs) {
                        allSongs.firstOrNull { it.songKey == pin.id }
                    }
                    if (song != null) {
                        MediaSheetItem("Go to album") {
                            onDismiss()
                            songNav.openAlbumForSong(song)
                        }
                        MediaSheetItem("Go to artist") {
                            onDismiss()
                            val name = song.effectiveAlbumArtist ?: song.artist
                            if (!name.isNullOrBlank()) songNav.openArtistByName(name)
                            else Toast.makeText(context, "No artist tag", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                StuffPinKind.ALBUM -> {
                    val album = remember(pin.id, allSongs) {
                        library.albums(taggedOnly = false)
                            .firstOrNull { albumKey(it.name, it.artist) == pin.id }
                    }
                    if (album != null) {
                        MediaSheetItem("Start radio") {
                            onDismiss()
                            albumNav.startRadio(album)
                        }
                        MediaSheetItem("Add to queue") {
                            onDismiss()
                            albumNav.addToQueue(album)
                        }
                        if (album.artist?.isNotBlank() == true) {
                            MediaSheetItem("Go to artist") {
                                onDismiss()
                                albumNav.openArtist(album)
                            }
                        }
                        MediaSheetItem("Edit album metadata") {
                            onDismiss()
                            albumNav.editMetadata(album)
                        }
                    } else {
                        MediaSheetItem("Open album") {
                            onDismiss()
                            // Best-effort: reconstruct a minimal AlbumItem for open
                            albumNav.openAlbum(
                                AlbumItem(
                                    name = pin.title,
                                    artist = pin.subtitle.takeIf { it.isNotBlank() },
                                    trackCount = 0,
                                    songs = emptyList()
                                )
                            )
                        }
                    }
                }
                StuffPinKind.ARTIST -> {
                    val artist = remember(pin.id, allSongs) {
                        library.artists(taggedOnly = false)
                            .firstOrNull { artistKey(it.name) == pin.id }
                    }
                    val name = artist?.displayName ?: pin.title
                    MediaSheetItem("Start radio") {
                        onDismiss()
                        artistNav.startRadio(name)
                    }
                    MediaSheetItem("Go to artist") {
                        onDismiss()
                        if (artist != null) artistNav.openArtist(artist)
                        else artistNav.openArtistByName(name)
                    }
                    // Image options only when provided (e.g. nested provider on artist page)
                    artistNav.fetchImage?.let { fn ->
                        MediaSheetItem("Fetch artist image") {
                            onDismiss()
                            fn(name)
                        }
                    }
                    artistNav.changeImage?.let { fn ->
                        MediaSheetItem("Change artist image") {
                            onDismiss()
                            fn(name)
                        }
                    }
                    artistNav.fetchBanner?.let { fn ->
                        MediaSheetItem("Fetch banner") {
                            onDismiss()
                            fn(name)
                        }
                    }
                    artistNav.changeBanner?.let { fn ->
                        MediaSheetItem("Change banner") {
                            onDismiss()
                            fn(name)
                        }
                    }
                }
                StuffPinKind.PLAYLIST -> {
                    val pl = playlists.firstOrNull { it.id == pin.id }
                    if (pl != null) {
                        MediaSheetItem("Start radio") {
                            onDismiss()
                            playlistNav.startRadio(pl)
                        }
                        MediaSheetItem("Open playlist") {
                            onDismiss()
                            playlistNav.openPlaylist(pl.id)
                        }
                        playlistNav.changeCover?.let { fn ->
                            MediaSheetItem("Change cover") {
                                onDismiss()
                                fn(pl.id)
                            }
                        }
                        playlistNav.edit?.let { fn ->
                            MediaSheetItem("Edit playlist") {
                                onDismiss()
                                fn(pl.id)
                            }
                        }
                    } else {
                        MediaSheetItem("Open playlist") {
                            onDismiss()
                            playlistNav.openPlaylist(pin.id)
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MediaSheetItem("Reorder pins") {
                onReorder()
            }
            MediaSheetItem("Remove pin", danger = true) {
                onUnpin()
            }
        }
    }
}
