package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.staticCompositionLocalOf
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.Song

/**
 * App-wide media navigation / actions.
 *
 * Provided once from [capital.yuri.yuriplayer.activities.YuriApp].
 * Context sheets and rows default to these so call sites only override
 * when they need different behavior (e.g. hide Go to album on the album page,
 * or add image/metadata options on the artist page).
 */

data class SongNavActions(
    val openAlbumForSong: (Song) -> Unit = {},
    val openArtistByName: (String) -> Unit = {}
)

data class AlbumNavActions(
    val openAlbum: (AlbumItem) -> Unit = {},
    val openArtist: (AlbumItem) -> Unit = {},
    val startRadio: (AlbumItem) -> Unit = {},
    val addToQueue: (AlbumItem) -> Unit = {},
    val editMetadata: (AlbumItem) -> Unit = {},
    val addToMyStuff: (AlbumItem) -> Unit = {}
)

data class ArtistNavActions(
    val openArtist: (ArtistItem) -> Unit = {},
    val openArtistByName: (String) -> Unit = {},
    val startRadio: (String) -> Unit = {},
    val addToMyStuff: (ArtistItem) -> Unit = {},
    /** Optional — only provided on screens that can handle image pick/crop. */
    val changeImage: ((artistName: String) -> Unit)? = null,
    val fetchImage: ((artistName: String) -> Unit)? = null,
    val changeBanner: ((artistName: String) -> Unit)? = null,
    val fetchBanner: ((artistName: String) -> Unit)? = null
)

data class PlaylistNavActions(
    val openPlaylist: (playlistId: String) -> Unit = {},
    val startRadio: (Playlist) -> Unit = {},
    val addToMyStuff: (Playlist) -> Unit = {},
    /** Optional — only provided on playlist detail / host that can edit. */
    val changeCover: ((playlistId: String) -> Unit)? = null,
    val edit: ((playlistId: String) -> Unit)? = null,
    val delete: ((playlistId: String) -> Unit)? = null
)

val LocalSongNav = staticCompositionLocalOf { SongNavActions() }
val LocalAlbumNav = staticCompositionLocalOf { AlbumNavActions() }
val LocalArtistNav = staticCompositionLocalOf { ArtistNavActions() }
val LocalPlaylistNav = staticCompositionLocalOf { PlaylistNavActions() }
