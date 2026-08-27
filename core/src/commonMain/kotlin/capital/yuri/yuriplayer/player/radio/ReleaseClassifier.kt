package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem

/**
 * Heuristic release typing until tags/MB release-group types are reliable.
 * Matches the Spotify-ish bands we already use on artist pages.
 */
object ReleaseClassifier {

    fun kindOf(album: AlbumItem): ReleaseKind {
        val n = album.trackCount
        return when {
            n <= 0 -> ReleaseKind.UNKNOWN
            n == 1 -> ReleaseKind.SINGLE
            n <= 6 -> ReleaseKind.EP
            else -> ReleaseKind.LP
        }
    }

    fun releaseKey(album: AlbumItem): String {
        val a = (album.artist ?: "").trim().lowercase()
        val n = (album.name ?: "").trim().lowercase()
        return "$a|$n"
    }
}
