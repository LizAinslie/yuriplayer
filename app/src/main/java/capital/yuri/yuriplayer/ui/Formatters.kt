package capital.yuri.yuriplayer.ui

/** English plural helpers — swap for string resources when i18n lands. */
fun formatTrackCount(count: Int): String =
    if (count == 1) "1 track" else "$count tracks"

fun formatAlbumCount(count: Int): String =
    if (count == 1) "1 album" else "$count albums"

/** mm:ss (or h:mm:ss for long tracks). */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
