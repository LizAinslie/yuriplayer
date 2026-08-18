package capital.yuri.yuriplayer.data

import android.net.Uri
import capital.yuri.yuriplayer.data.json.UriAsStringSerializer
import kotlinx.serialization.Serializable
import java.io.File

/**
 * All tag fields are nullable — missing tags stay null rather than "Unknown …".
 * Use [displayTitle] / [displayArtist] / [displayAlbum] only for UI strings.
 */
@Serializable
data class Song(
    val id: Long,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    @Serializable(with = UriAsStringSerializer::class)
    val contentUri: Uri,
    @Serializable(with = UriAsStringSerializer::class)
    val albumArtUri: Uri? = null,
    val trackNumber: Int? = null,
    /** Disc / media set number (1-based). Null = single-disc or unknown. */
    val discNumber: Int? = null,
    val year: Int? = null,
    /** Genre tag (may be semicolon-separated). */
    val genre: String? = null,
    val path: String? = null,
    val mimeType: String? = null
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path?.let { File(it).nameWithoutExtension }
            ?: "Unknown"

    val displayArtist: String
        get() = artist?.takeIf { it.isMeaningfulTag() }
            ?: albumArtist?.takeIf { it.isMeaningfulTag() }
            ?: "Unknown Artist"

    val displayAlbum: String
        get() = album?.takeIf { it.isMeaningfulTag() } ?: "Unknown Album"

    val effectiveAlbumArtist: String?
        get() = albumArtist?.takeIf { it.isMeaningfulTag() }
            ?: artist?.takeIf { it.isMeaningfulTag() }

    val displayAlbumArtist: String
        get() = effectiveAlbumArtist ?: "Unknown Artist"

    /** Parsed genre list from semicolon / slash / comma separated tag. */
    val genres: List<String>
        get() = genre.orEmpty()
            .split(';', '/', ',', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    val creditArtists: List<String>
        get() {
            val fromArtist = parseArtistCredits(artist)
            if (fromArtist.isNotEmpty()) return fromArtist
            val fromAlbum = parseArtistCredits(albumArtist)
            if (fromAlbum.isNotEmpty()) return fromAlbum
            return emptyList()
        }

    val isTagged: Boolean
        get() = artist.isMeaningfulTag() ||
            albumArtist.isMeaningfulTag() ||
            album.isMeaningfulTag()

    val hasAlbum: Boolean get() = album.isMeaningfulTag()
    val hasArtist: Boolean get() = artist.isMeaningfulTag() || albumArtist.isMeaningfulTag()
    val hasTitle: Boolean get() = title.isMeaningfulTag()

    val songKey: String
        get() = path?.lowercase() ?: contentUri.toString()

    fun isSameAs(other: Song?): Boolean {
        if (other == null) return false
        if (path != null && other.path != null) return path == other.path
        return id == other.id && contentUri == other.contentUri
    }
}

private fun String?.isMeaningfulTag(): Boolean {
    if (this == null) return false
    val t = trim()
    if (t.isEmpty()) return false
    if (t.equals("<unknown>", true)) return false
    if (t.equals("Unknown", true)) return false
    if (t.equals("Unknown Artist", true)) return false
    if (t.equals("Unknown Album", true)) return false
    return true
}

enum class SortMode {
    TITLE,
    ARTIST,
    ALBUM,
    TRACK
}

fun SortMode.label(): String = when (this) {
    SortMode.TITLE -> "Title"
    SortMode.ARTIST -> "Artist"
    SortMode.ALBUM -> "Album"
    SortMode.TRACK -> "Track #"
}

fun albumKey(name: String?, artist: String?): String {
    val a = (artist ?: "").trim().lowercase()
    val n = (name ?: "").trim().lowercase()
    return "$a|$n"
}

fun artistKey(name: String?): String? {
    if (name == null) return null
    val t = name.trim().replace(Regex("\\s+"), " ").lowercase()
    return t.takeIf { it.isNotEmpty() }
}
