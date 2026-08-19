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
    val mimeType: String? = null,
    /** Explicit content flag when known from tags / remote metadata. */
    val explicit: Boolean = false
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

    /** Spotify-style explicit: flag or common title/genre markers. */
    val isExplicit: Boolean
        get() {
            if (explicit) return true
            val t = title.orEmpty()
            val g = genre.orEmpty()
            return t.contains("explicit", ignoreCase = true) ||
                t.contains("[e]", ignoreCase = true) ||
                Regex("\\(e\\)", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                g.contains("explicit", ignoreCase = true)
        }

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

/**
 * Cross-source track identity.
 *
 * Titles match with or without `(feat. …)` / `ft.` / `featuring …`.
 * Track artist may differ so long as album + album artist line up — common when
 * one source tags "Artist" and another tags featured credits differently.
 *
 * For **display**, prefer the richest tags across offerings (title with feat.
 * beats a bare title) while playback URI still follows source preference.
 */
object TrackIdentity {
    private val FEAT_IN_PARENS = Regex(
        """(?i)[\(\[\{]\s*(feat\.?|ft\.?|featuring)\s+[^\)\]\}]+[\)\]\}]"""
    )
    private val FEAT_SUFFIX = Regex(
        """(?i)\s+(feat\.?|ft\.?|featuring)\s+.+$"""
    )
    private val WS = Regex("\\s+")

    fun normalizeTitle(title: String?): String {
        if (title == null) return ""
        var t = title.trim()
        t = FEAT_IN_PARENS.replace(t, "")
        t = FEAT_SUFFIX.replace(t, "")
        return t.replace(WS, " ").trim().lowercase()
    }

    fun normalizeToken(value: String?): String {
        if (value == null) return ""
        return value.trim().replace(WS, " ").lowercase()
    }

    /** Stable key used for multi-source grouping + preferred-source overrides. */
    fun of(song: Song): String {
        val title = normalizeTitle(song.title)
        val album = normalizeToken(song.album)
        // Anchor on album artist (not track artist) so feat. credit drift is ok
        val albumArtist = normalizeToken(song.effectiveAlbumArtist)
        return when {
            title.isNotEmpty() && (album.isNotEmpty() || albumArtist.isNotEmpty()) ->
                "$title|$albumArtist|$album"
            title.isNotEmpty() ->
                "$title|${normalizeToken(song.artist)}|$album"
            else -> song.songKey
        }
    }

    fun titlesMatch(a: String?, b: String?): Boolean {
        val na = normalizeTitle(a)
        val nb = normalizeTitle(b)
        return na.isNotEmpty() && na == nb
    }

    fun albumsMatch(a: String?, b: String?): Boolean {
        val na = normalizeToken(a)
        val nb = normalizeToken(b)
        return na.isNotEmpty() && na == nb
    }

    fun albumArtistsMatch(a: String?, b: String?): Boolean {
        val na = normalizeToken(a)
        val nb = normalizeToken(b)
        return na == nb
    }

    /**
     * True when two songs are the same logical track across sources.
     * Requires normalized title + album; album-artist may be empty on one side;
     * track artist is intentionally ignored when album + album artist agree.
     */
    fun matches(a: Song, b: Song): Boolean {
        if (!titlesMatch(a.title, b.title)) return false
        val albumA = normalizeToken(a.album)
        val albumB = normalizeToken(b.album)
        if (albumA.isNotEmpty() && albumB.isNotEmpty() && albumA != albumB) return false
        val aaA = normalizeToken(a.effectiveAlbumArtist)
        val aaB = normalizeToken(b.effectiveAlbumArtist)
        if (aaA.isNotEmpty() && aaB.isNotEmpty() && aaA != aaB) return false
        // If both album and album-artist missing, fall back to track artist
        if (albumA.isEmpty() && albumB.isEmpty() && aaA.isEmpty() && aaB.isEmpty()) {
            val ta = normalizeToken(a.artist)
            val tb = normalizeToken(b.artist)
            if (ta.isNotEmpty() && tb.isNotEmpty() && ta != tb) return false
        }
        return true
    }

    /** Prefer titles that include feat./ft./featuring credits, then longer text. */
    fun titleDetailScore(title: String?): Int {
        if (title.isNullOrBlank()) return -1
        val t = title.trim()
        var score = t.length
        if (FEAT_IN_PARENS.containsMatchIn(t) || FEAT_SUFFIX.containsMatchIn(t)) {
            score += 100
        } else {
            val lower = t.lowercase()
            if (lower.contains("feat.") || lower.contains("ft.") || lower.contains("featuring")) {
                score += 80
            }
        }
        return score
    }

    /** Prefer artist lines with more credit detail (feat, commas, &). */
    fun artistDetailScore(artist: String?): Int {
        if (artist.isNullOrBlank()) return -1
        val a = artist.trim()
        var score = a.length
        val lower = a.lowercase()
        if (lower.contains("feat") || lower.contains("ft.")) score += 40
        if (a.contains(',') || a.contains('&') || a.contains(';')) score += 20
        return score
    }

    /**
     * Keep [playback]'s URI/path (source preference) but upgrade display tags from
     * the richest candidate — e.g. "Song (feat. X)" over bare "Song".
     */
    fun withRichestDisplay(playback: Song, candidates: List<Song>): Song {
        if (candidates.isEmpty()) return playback
        val all = candidates + playback
        val bestTitle = all.mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }
            .maxByOrNull { titleDetailScore(it) }
        val bestArtist = all.mapNotNull { it.artist?.takeIf { t -> t.isNotBlank() } }
            .maxByOrNull { artistDetailScore(it) }
        val bestAlbumArtist = all.mapNotNull { it.albumArtist?.takeIf { t -> t.isNotBlank() } }
            .maxByOrNull { artistDetailScore(it) }
        val bestAlbum = all.mapNotNull { it.album?.takeIf { t -> t.isNotBlank() } }
            .maxByOrNull { it.length }
        val anyExplicit = all.any { it.explicit || it.isExplicit }

        return playback.copy(
            title = when {
                bestTitle != null && titleDetailScore(bestTitle) > titleDetailScore(playback.title) ->
                    bestTitle
                else -> playback.title
            },
            artist = when {
                bestArtist != null && artistDetailScore(bestArtist) > artistDetailScore(playback.artist) ->
                    bestArtist
                else -> playback.artist
            },
            albumArtist = when {
                bestAlbumArtist != null &&
                    artistDetailScore(bestAlbumArtist) > artistDetailScore(playback.albumArtist) ->
                    bestAlbumArtist
                else -> playback.albumArtist
            },
            album = when {
                bestAlbum != null &&
                    (playback.album.isNullOrBlank() || bestAlbum.length > playback.album.length) ->
                    bestAlbum
                else -> playback.album
            },
            explicit = playback.explicit || anyExplicit
        )
    }
}
