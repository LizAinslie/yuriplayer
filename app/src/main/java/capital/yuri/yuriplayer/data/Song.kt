package capital.yuri.yuriplayer.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri? = null,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val path: String? = null,
    val mimeType: String? = null
)

enum class SortMode {
    TITLE,
    ARTIST,
    ALBUM,
    TRACK
}
