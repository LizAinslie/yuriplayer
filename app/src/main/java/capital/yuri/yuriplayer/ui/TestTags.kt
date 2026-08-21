package capital.yuri.yuriplayer.ui

/**
 * Compose [androidx.compose.ui.platform.testTag] ids for on-device UI tests.
 * Keep these stable — instrumented tests and Maestro flows both use them.
 */
object TestTags {
    const val TAB_HOME = "tab_home"
    const val TAB_MY_STUFF = "tab_my_stuff"
    const val TAB_EXPLORE = "tab_explore"
    const val TAB_SEARCH = "tab_search"
    const val SETTINGS = "settings"

    const val MYSTUFF_PINS = "mystuff_pins"
    const val MYSTUFF_CATALOG = "mystuff_catalog"
    const val MYSTUFF_PLAYLISTS = "mystuff_playlists"
    const val CATALOG_TITLE = "catalog_title"

    const val MINI_PLAYER = "mini_player"
    const val MINI_TITLE = "mini_title"
    const val MINI_ARTIST = "mini_artist"
    const val MINI_PLAY_PAUSE = "mini_play_pause"

    const val NOW_PLAYING = "now_playing"
    const val NP_TITLE = "np_title"
    const val NP_ARTIST = "np_artist"
    const val NP_ALBUM = "np_album"
    const val NP_PLAY_PAUSE = "np_play_pause"
    const val NP_SKIP_NEXT = "np_skip_next"
    const val NP_SKIP_PREV = "np_skip_prev"
    const val NP_CLOSE = "np_close"

    const val SETTINGS_PLAYBACK_ENGINE = "settings_playback_engine"
    const val ENGINE_MEDIA3 = "engine_media3"
    const val ENGINE_VLC = "engine_vlc"

    const val QUEUE_SKIP_NEXT = "queue_skip_next"
    const val QUEUE_SKIP_PREV = "queue_skip_prev"
    const val QUEUE_PLAY_PAUSE = "queue_play_pause"
}
