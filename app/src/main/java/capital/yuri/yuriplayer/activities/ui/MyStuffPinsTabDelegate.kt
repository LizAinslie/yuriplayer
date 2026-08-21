package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.Composable
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin

/**
 * Drop-in replacement used from [MyStuffScreen].
 * Prefer calling [MyStuffPinsHost] directly; this exists so a one-line
 * rename in MyStuffScreen is enough if the private tab is still referenced.
 */
@Composable
fun MyStuffPinsTabPublic(
    pins: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onOpenPin: (StuffPin) -> Unit,
    onUnpin: (StuffPin) -> Unit,
    onAddPinSlot: () -> Unit
) {
    MyStuffPinsHost(
        pins = pins,
        library = library,
        playlists = playlists,
        allSongs = allSongs,
        onOpenPin = onOpenPin,
        onUnpin = onUnpin,
        onAddPinSlot = onAddPinSlot
    )
}
