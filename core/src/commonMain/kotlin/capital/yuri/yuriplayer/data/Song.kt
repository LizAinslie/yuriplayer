package capital.yuri.yuriplayer.data

import kotlinx.serialization.Serializable

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
    val contentUri: String,
    val albumArtUri: String? = null,
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
    val musicBrainzArtistId: String? = null,
    /**
     * Remote account id (`"local"`, a Jellyfin/Subsonic account UUID, …).
     * Cross-platform so a [Song] can be refreshed with a live stream URL
     * before playback without the host keeping an out-of-band identity map.
     */
    val sourceId: String? = null
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path?.let { it.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
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
        get() = path?.lowercase() ?: contentUri

    fun isSameAs(other: Song?): Boolean {
        if (other == null) return false
        if (path != null && other.path != null) return path == other.path
        return id == other.id && contentUri == other.contentUri
    }

    companion object {
        /**
         * Minimal cross-platform construction. Platform-specific factories
         * (e.g. from an Android `Uri` or a JVM `File`) live in per-platform
         * extension functions and delegate here.
         */
        operator fun invoke(
            id: Long,
            contentUri: String,
            path: String? = null,
            title: String? = null,
            artist: String? = null,
            album: String? = null,
            albumArtUri: String? = null,
            durationMs: Long? = null
        ): Song = Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            contentUri = contentUri,
            albumArtUri = albumArtUri,
            path = path
        )
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

/** Cross-platform accent folding (replaces `java.text.Normalizer` NFKD). */
private val ACCENT_MAP: Map<Char, Char> = mapOf(
    'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a',
    'ç' to 'c',
    'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
    'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
    'ñ' to 'n',
    'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
    'ý' to 'y', 'ÿ' to 'y',
    'À' to 'A', 'Á' to 'A', 'Â' to 'A', 'Ã' to 'A', 'Ä' to 'A', 'Å' to 'A',
    'Ç' to 'C',
    'È' to 'E', 'É' to 'E', 'Ê' to 'E', 'Ë' to 'E',
    'Ì' to 'I', 'Í' to 'I', 'Î' to 'I', 'Ï' to 'I',
    'Ñ' to 'N',
    'Ò' to 'O', 'Ó' to 'O', 'Ô' to 'O', 'Õ' to 'O', 'Ö' to 'O',
    'Ù' to 'U', 'Ú' to 'U', 'Û' to 'U', 'Ü' to 'U',
    'Ý' to 'Y'
)

private fun stripAccents(s: String): String =
    s.map { ACCENT_MAP[it] ?: it }.joinToString("")

/**
 * Fold stylized / accented music tags so "Twenty Øne Piløts" and
 * "Twenty One Pilots" share the same catalog keys.
 */
fun foldTagToken(value: String?): String {
    var t = value.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()
    t = stripAccents(t)
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
fun foldTitleToken(value: String?): String {
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

    fun normalizeTitle(raw: String?): String =
        raw.orEmpty()
            .replace(FEAT_IN_PARENS, " ")
            .replace(FEAT_SUFFIX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Folded token for name/album/artist comparisons. */
    fun normalizeToken(raw: String?): String = foldTagToken(raw)

    fun of(song: Song): String {
        val t = foldTitleToken(normalizeTitle(song.title))
        val al = foldTagToken(song.album ?: "")
        val aa = foldTagToken(song.effectiveAlbumArtist ?: "")
        return "k:$t|$aa|$al"
    }

    fun titlesMatch(a: String?, b: String?): Boolean =
        foldTitleToken(normalizeTitle(a)) == foldTitleToken(normalizeTitle(b))

    fun titlesNearlyMatch(a: String?, b: String?, minLen: Int = 6): Boolean {
        val ta = normalizeTitle(a)
        val tb = normalizeTitle(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta.length < minLen && tb.length < minLen) return ta.equals(tb, true)
        val ftA = foldTitleToken(ta)
        val ftB = foldTitleToken(tb)
        if (ftA == ftB) return true
        return editDistanceAtMost(ftA, ftB, 2) <= 2
    }

    fun albumsMatch(a: String?, b: String?): Boolean =
        foldTagToken(a) == foldTagToken(b)

    fun albumsNearlyMatch(a: String?, b: String?, max: Int = 2): Boolean {
        val fa = foldTagToken(a)
        val fb = foldTagToken(b)
        if (fa.isEmpty() || fb.isEmpty()) return false
        if (fa == fb) return true
        return editDistanceAtMost(fa, fb, max) <= max
    }

    /**
     * Loose album-artist comparison: ignores `feat.` credits and ordering of
     * multiple (comma/slash/ampersand-separated) artists.
     */
    fun albumArtistsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val fa = normalizeToken(a)
        val fb = normalizeToken(b)
        if (fa == fb) return true
        return fa.contains(fb) || fb.contains(fa)
    }

    fun matches(a: Song, b: Song): Boolean =
        titlesNearlyMatch(a.title, b.title) &&
            (albumsMatch(a.album, b.album) ||
                albumsNearlyMatch(a.album, b.album) ||
                a.album.isNullOrBlank() ||
                b.album.isNullOrBlank())

    private fun titleDetailScore(t: String?): Int {
        if (t.isNullOrBlank()) return -1
        var score = t.trim().length
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
