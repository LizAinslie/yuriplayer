package capital.yuri.yuriplayer.data.organize

import capital.yuri.yuriplayer.data.Song
import java.util.Locale

/**
 * Path layout for one library root (SAF tree URI, filesystem root, or future
 * folder-like remote mount id).
 *
 * Tokens: {albumArtist} {artist} {album} {title} {track} {disc} {year} {ext}
 */
data class OrganizeLayout(
    /** Opaque root key — usually the SAF tree URI string. */
    val rootKey: String,
    /** Pattern when album tag is present. */
    val albumPattern: String = DEFAULT_ALBUM,
    /** Pattern when album is missing / Unknown. */
    val singlePattern: String = DEFAULT_SINGLE,
    val collision: CollisionPolicy = CollisionPolicy.SUFFIX,
    /** Folder for tracks missing required tokens after sanitization. */
    val unsortedFolder: String = "_unsorted",
    val enabled: Boolean = true
) {
    enum class CollisionPolicy {
        /** Leave the existing file; skip the move. */
        SKIP,
        /** Append " (2)", " (3)", … before the extension. */
        SUFFIX,
        /** Replace the destination document. */
        OVERWRITE
    }

    companion object {
        const val DEFAULT_ALBUM =
            "{albumArtist}/{album}/{track} - {title}.{ext}"
        const val DEFAULT_SINGLE =
            "{albumArtist}/Singles/{title}.{ext}"

        val TOKEN_HELP = listOf(
            "{albumArtist}" to "Album artist, else track artist",
            "{artist}" to "Track artist",
            "{album}" to "Album title",
            "{title}" to "Track title",
            "{track}" to "Track number, zero-padded (01)",
            "{disc}" to "Disc number if present",
            "{year}" to "Year if tagged",
            "{ext}" to "File extension without dot"
        )
    }
}

object PathTemplate {

    fun expand(pattern: String, song: Song, extOverride: String? = null): String {
        val ext = extOverride
            ?: song.path?.substringAfterLast('.', "")?.lowercase(Locale.US)
            ?: song.contentUri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.substringAfterLast('%')
                ?.lowercase(Locale.US)
            ?: "flac"

        val albumArtist = song.albumArtist?.takeIf { it.isNotBlank() }
            ?: song.artist?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"
        val artist = song.artist?.takeIf { it.isNotBlank() } ?: albumArtist
        val album = song.album?.takeIf { it.isNotBlank() }
        val title = song.title?.takeIf { it.isNotBlank() }
            ?: song.path?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Unknown Title"
        val track = song.trackNumber?.let { String.format(Locale.US, "%02d", it) } ?: ""
        val disc = song.discNumber?.takeIf { it > 0 }?.toString().orEmpty()
        val year = song.year?.toString().orEmpty()

        var out = pattern
        out = replaceToken(out, "albumArtist", sanitize(albumArtist))
        out = replaceToken(out, "artist", sanitize(artist))
        out = replaceToken(out, "album", sanitize(album ?: "Unknown Album"))
        out = replaceToken(out, "title", sanitize(title))
        out = replaceToken(out, "track", track)
        out = replaceToken(out, "disc", disc)
        out = replaceToken(out, "year", year)
        out = replaceToken(out, "ext", ext.filter { it.isLetterOrDigit() }.ifBlank { "audio" })

        // Collapse empty path segments from blank optional tokens
        return out
            .split('/')
            .map { it.trim().trim('.', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .ifBlank { "_unsorted/${sanitize(title)}.${ext}" }
    }

    fun isAlbumTrack(song: Song): Boolean =
        !song.album.isNullOrBlank()

    fun relativePathFor(layout: OrganizeLayout, song: Song): String {
        val pattern = if (isAlbumTrack(song)) layout.albumPattern else layout.singlePattern
        return expand(pattern, song)
    }

    private fun replaceToken(pattern: String, name: String, value: String): String {
        return pattern
            .replace("{$name}", value, ignoreCase = true)
            .replace("{{$name}}", value, ignoreCase = true)
    }

    /** Strip path separators and control chars from a single segment. */
    fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (ch in raw.trim()) {
                when {
                    ch == '/' || ch == '\\' || ch == ':' || ch == '*' ||
                        ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|' ->
                        append('_')
                    ch.code < 32 -> Unit
                    else -> append(ch)
                }
            }
        }.trim().trim('.', ' ')
        return cleaned.ifBlank { "Unknown" }.take(120)
    }
}
