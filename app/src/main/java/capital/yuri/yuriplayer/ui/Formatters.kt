package capital.yuri.yuriplayer.ui

/** English plural helpers — swap for string resources when i18n lands. */
fun formatTrackCount(count: Int): String =
    if (count == 1) "1 track" else "$count tracks"

fun formatAlbumCount(count: Int): String =
    if (count == 1) "1 album" else "$count albums"
