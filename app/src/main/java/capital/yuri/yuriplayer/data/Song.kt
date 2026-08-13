package capital.yuri.yuriplayer.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val albumArtist: String = "",
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri? = null,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val path: String? = null,
    val mimeType: String? = null
) {
    /** Album artist if tagged, otherwise track artist. */
    val effectiveAlbumArtist: String
        get() = albumArtist.takeIf { it.isNotBlank() && !it.isUnknownArtist() }
            ?: artist.takeIf { it.isNotBlank() && !it.isUnknownArtist() }
            ?: "Unknown Artist"

    /** True when the file has usable title/artist/album tags (not filename fallbacks). */
    val isTagged: Boolean
        get() {
            val unknownArtist = artist.isUnknownArtist()
            val unknownAlbum = album.isUnknownAlbum()
            // Untagged: both artist and album are missing/placeholder
            return !(unknownArtist && unknownAlbum)
        }
}

private fun String.isUnknownArtist(): Boolean {
    val t = trim()
    return t.isEmpty() || t.equals("Unknown Artist", true) || t.equals("<unknown>", true)
}

private fun String.isUnknownAlbum(): Boolean {
    val t = trim()
    return t.isEmpty() || t.equals("Unknown Album", true) || t.equals("<unknown>", true)
}

enum class SortMode {
    TITLE,
    ARTIST,
    ALBUM,
    TRACK
}
