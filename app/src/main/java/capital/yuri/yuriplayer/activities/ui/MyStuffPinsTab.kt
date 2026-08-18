package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.Composable
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StuffPin

/**
 * Public pins tab entry used by [MyStuffScreen].
 * Forwards to [MyStuffPinsHost] (surface cards, long-press sheet with Reorder pins, drag mode).
 *
 * Named without "private" so MyStuffScreen can call it after we drop the private duplicate.
 * If MyStuffScreen still has a private MyStuffPinsTab, change that call site to this or to MyStuffPinsHost.
 */
@Composable
fun MyStuffPinsTabWired(
    pins: List<StuffPin>,
    library: LibraryIndex,
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onOpenPin: (StuffPin) -> Unit,
    onUnpin: (StuffPin) -> Unit,
    onAddPinSlot: () -> Unit,
    onPlayAll: () -> Unit
) = MyStuffPinsHost(
    pins = pins,
    library = library,
    playlists = playlists,
    allSongs = allSongs,
    onOpenPin = onOpenPin,
    onUnpin = onUnpin,
    onAddPinSlot = onAddPinSlot,
    onPlayAll = onPlayAll
)
