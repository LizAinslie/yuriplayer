package capital.yuri.yuriplayer.data

import android.net.Uri
import capital.yuri.yuriplayer.data.json.UriAsStringSerializer
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer

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
    val explicit: Boolean = false,
    /** MusicBrainz artist id when the source publishes one (Navidrome / OpenSubsonic). */
    val musicBrainzArtistId: String? = null
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path?.let { File(it).nameWithoutExtension }
            ?: "Unknown"

    val displayArtist: String
        get() = artist?.takeIf { it.isMeaningfulTag() }
            ?: albumArtist?.takeIf { it.isMeaningfulTag() }
            ?: "Unknown Artist"

    /** Album cards / explore — never includes feat. credits. */
    val primaryArtist: String
        get() = primaryArtistName(effectiveAlbumArtist)
            ?: primaryArtistName(artist)
            ?: displayArtist

    val displayAlbum: String
        get() = album?.takeIf { it.isMeaningfulTag() } ?: "Unknown Album"

    val effectiveAlbumArtist: String?
        get() = albumArtist?.takeIf { it.isMeaningfulTag() }
            ?: artist?.takeIf { it.isMeaningfulTag() }

    val displayAlbumArtist: String
        get() = effectiveAlbumArtist ?: "Unknown Artist"

    val genres: List<String>
        get() = genre.orEmpty()
            .split(';', '/', ',', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    val creditArtists: List<String>
        get() = allCreditsForSong(this).map { it.name }

    val isTagged: Boolean
        get() = artist.isMeaningfulTag() ||
            albumArtist.isMeaningfulTag() ||
            album.isMeaningfulTag()

    val hasAlbum: Boolean get() = album.isMeaningfulTag()
    val hasArtist: Boolean get() = artist.isMeaningfulTag() || albumArtist.isMeaningfulTag()
    val hasTitle: Boolean get() = title.isMeaningfulTag()

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

/**
 * Fold stylized / accented music tags so "Twenty Øne Piløts" and
 * "Twenty One Pilots" share the same catalog keys.
 */
fun foldTagToken(value: String): String {
    var t = value.trim().replace(Regex("\\s+"), " ").lowercase()
    t = Normalizer.normalize(t, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
    // Multi-char replacements MUST be Strings (Char literals only hold one code unit).
    t = t
        .replace('ø', 'o')
        .replace("æ", "ae")
        .replace("œ", "oe")
        .replace('ł', 'l')
        .replace('đ', 'd')
        .replace("ß", "ss")
    // Jenna's vs Jenna's (U+2019) split Clancy track 7 into two rows.
    t = t.replace(APOSTROPHE_LIKE, "")
    return t
}

/** Title identity: "Oh, Ms. Believer" and "Oh Ms. Believer" are the same song. */
fun foldTitleToken(value: String): String {
    var t = foldTagToken(value)
    t = t.replace(TITLE_DECORATIVE_PUNCT, " ")
    return t.replace(Regex("\\s+"), " ").trim()
}

private val APOSTROPHE_LIKE = Regex(
    "[\u0027\u0060\u00B4\u2018\u2019\u201A\u201B\u2032\u02BB\u02BC\uFF07]"
)

private val TITLE_DECORATIVE_PUNCT = Regex("[,.:;!?]")

/** Banded Levenshtein; returns [max] + 1 when the titles are farther than [max]. */
fun editDistanceAtMost(a: String, b: String, max: Int): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    val m = b.length
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..a.length) {
        cur[0] = i
        var rowMin = i
        val ca = a[i - 1]
        for (j in 1..m) {
            val cost = if (ca == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (cur[j] < rowMin) rowMin = cur[j]
        }
        if (rowMin > max) return max + 1
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return prev[m]
}

fun albumKey(name: String?, artist: String?): String {
    val a = artistKey(artist) ?: foldTagToken(primaryArtistName(artist) ?: artist ?: "")
    val n = foldTagToken(name ?: "")
    return "$a|$n"
}

/** Folded artist key before user / MBID aliases. */
fun rawArtistKey(name: String?): String? {
    val primary = primaryArtistName(name) ?: name ?: return null
    val t = foldTagToken(primary)
    return t.takeIf { it.isNotEmpty() }
}

fun artistKey(name: String?): String? {
    val raw = rawArtistKey(name) ?: return null
    return ArtistAliasResolver.resolve(raw)
}

/**
 * In-memory alias map (Nightcord → 25時、ナイトコードで。).
 * CatalogRepository loads Room rows into this at start / after merge.
 */
object ArtistAliasResolver {
    @Volatile
    private var map: Map<String, String> = emptyMap()

    @Volatile
    private var reverse: Map<String, List<String>> = emptyMap()

    fun replace(aliases: Map<String, String>) {
        map = aliases
        reverse = aliases.entries.groupBy({ it.value }, { it.key })
    }

    fun resolve(key: String): String {
        if (map.isEmpty()) return key
        var cur = key
        val seen = HashSet<String>(4)
        while (true) {
            val next = map[cur] ?: return cur
            if (next == cur || !seen.add(cur)) return next
            cur = next
        }
    }

    fun isAlias(key: String): Boolean = map.containsKey(key)

    fun aliasKeysOf(canonicalKey: String): List<String> = reverse[canonicalKey].orEmpty()

    /** Canonical key plus every alias that redirects here. */
    fun identityKeys(key: String): List<String> {
        val canonical = resolve(key)
        val aliases = aliasKeysOf(canonical)
        if (aliases.isEmpty()) return listOf(canonical)
        return buildList(aliases.size + 1) {
            add(canonical)
            addAll(aliases)
        }
    }
}

/**
 * Cross-source track identity.
 *
 * Titles match with or without `(feat. …)` / `ft.` / `featuring …`.
 * Track artist may differ so long as album + album artist line up.
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
        return foldTitleToken(t)
    }

    fun normalizeToken(value: String?): String {
        if (value == null) return ""
        return foldTagToken(value)
    }

    fun of(song: Song): String {
        val title = normalizeTitle(song.title)
        val album = normalizeToken(song.album)
        val albumArtist = foldTagToken(
            primaryArtistName(song.effectiveAlbumArtist) ?: song.effectiveAlbumArtist ?: ""
        )
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

    /**
     * Same release with a one-letter server typo
     * ("…Fray" vs "…Frat"). Short titles stay exact so Trench/Breach don't merge.
     */
    fun albumsNearlyMatch(a: String?, b: String?): Boolean {
        if (albumsMatch(a, b)) return true
        val na = normalizeToken(a)
        val nb = normalizeToken(b)
        if (na.length < 16 || nb.length < 16) return false
        if (kotlin.math.abs(na.length - nb.length) > 2) return false
        return editDistanceAtMost(na, nb, 2) <= 2
    }

    fun albumArtistsMatch(a: String?, b: String?): Boolean {
        val pa = foldTagToken(primaryArtistName(a) ?: a ?: "")
        val pb = foldTagToken(primaryArtistName(b) ?: b ?: "")
        if (pa.isNotEmpty() && pb.isNotEmpty()) return pa == pb
        return normalizeToken(a) == normalizeToken(b)
    }

    fun matches(a: Song, b: Song): Boolean {
        if (!titlesMatch(a.title, b.title)) return false
        val albumA = normalizeToken(a.album)
        val albumB = normalizeToken(b.album)
        if (albumA.isNotEmpty() && albumB.isNotEmpty() && albumA != albumB) return false
        if (!albumArtistsMatch(a.effectiveAlbumArtist, b.effectiveAlbumArtist)) {
            val aaA = foldTagToken(primaryArtistName(a.effectiveAlbumArtist) ?: "")
            val aaB = foldTagToken(primaryArtistName(b.effectiveAlbumArtist) ?: "")
            if (aaA.isNotEmpty() && aaB.isNotEmpty()) return false
        }
        if (albumA.isEmpty() && albumB.isEmpty()) {
            val ta = foldTagToken(primaryArtistName(a.artist) ?: a.artist ?: "")
            val tb = foldTagToken(primaryArtistName(b.artist) ?: b.artist ?: "")
            if (ta.isNotEmpty() && tb.isNotEmpty() && ta != tb) return false
        }
        return true
    }

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

    fun artistDetailScore(artist: String?): Int {
        if (artist.isNullOrBlank()) return -1
        val a = artist.trim()
        var score = a.length
        val lower = a.lowercase()
        if (lower.contains("feat") || lower.contains("ft.")) score += 40
        if (a.contains(',') || a.contains('&') || a.contains(';')) score += 20
        return score
    }

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
