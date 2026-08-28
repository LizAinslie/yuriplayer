package capital.yuri.yuriplayer.data

enum class HomeRowId(val id: String, val label: String, val subtitle: String) {
    RECENTS("recents", "Recents", "Recently played, newest first"),
    RANDOM_ALBUMS("random_albums", "Random albums", "A handful from My Stuff"),
    RANDOM_SONGS("random_songs", "Random songs", "A handful from My Stuff"),
    RANDOM_PLAYLISTS("random_playlists", "Random playlists", "A handful from My Stuff"),
    RANDOM_ARTISTS("random_artists", "Random artists", "A handful from My Stuff")
}
