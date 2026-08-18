package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.staticCompositionLocalOf
import capital.yuri.yuriplayer.data.Song

/**
 * App-wide song navigation used by [SwipeAddSongRow] / [SongContextSheet]
 * so every long-press sheet can offer Go to album / Go to artist without
 * each call site wiring callbacks.
 */
data class SongNavActions(
    val openAlbumForSong: (Song) -> Unit = {},
    val openArtistByName: (String) -> Unit = {}
)

val LocalSongNav = staticCompositionLocalOf { SongNavActions() }
