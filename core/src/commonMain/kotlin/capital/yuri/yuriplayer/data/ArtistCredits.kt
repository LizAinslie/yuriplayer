package capital.yuri.yuriplayer.data

/**
 * Split a raw artist tag into primary vs featured credits.
 *
 * Separators:
 *  - `;` always splits peers
 *  - `feat.` / `ft.` / `featuring` starts the featured list
 *  - commas/`&` in the *primary* blob stay inside names ("Earth, Wind & Fire")
 *  - commas/`&`/`x` in a *feat.* blob split guest lists ("Miku, Rin & Luka")
 */
enum class ArtistRole {
    PRIMARY,
    FEATURED
}

data class ArtistCredit(
    val name: String,
    val role: ArtistRole,
    val position: Int
)

private val FEAT_SPLIT = Regex(
    """(?i)(?:\p{Zs}+|[\(\[\{]\p{Zs}*)(?:feat\.?|ft\.?|featuring)\.?\p{Zs}*"""
)
private val FEAT_IN_PARENS = Regex(
    """(?i)[\(\[\{]\p{Zs}*(?:feat\.?|ft\.?|featuring)\.?\p{Zs}*([^\)\]\}]+)[\)\]\}]"""
)
private val FEAT_SUFFIX = Regex(
    """(?i)\p{Zs}+(?:feat\.?|ft\.?|featuring)\.?\p{Zs}*(.+)$"""
)
private val FEAT_BLOB_SPLIT = Regex("""\s*(?:;|,|&|×)\s+|\s+(?:and|x)\s+""", RegexOption.IGNORE_CASE)
private val TRAILING_OPEN = Regex("""[\(\[\{]\p{Zs}*$""")

fun parseArtistCreditList(raw: String?): List<ArtistCredit> {
    if (raw.isNullOrBlank()) return emptyList()
    val parenFeats = ArrayList<String>()
    var cleaned = raw.trim()
    FEAT_IN_PARENS.findAll(cleaned).forEach { parenFeats += splitFeatBlob(it.groupValues[1]) }
    cleaned = cleaned.replace(FEAT_IN_PARENS, " ").replace(Regex("""\s+"""), " ").trim()
    cleaned = cleaned.replace(TRAILING_OPEN, "").trim()

    val parts = cleaned.split(FEAT_SPLIT, limit = 2)
    val out = ArrayList<ArtistCredit>()
    fun addPrimary(blob: String) {
        blob.split(';').map { it.trim().trim('(', ')', '[', ']', '{', '}') }.filter { it.isNotEmpty() }.forEach { name ->
            if (out.none { it.name.equals(name, ignoreCase = true) }) {
                out += ArtistCredit(name = name, role = ArtistRole.PRIMARY, position = out.size)
            }
        }
    }
    fun addFeatured(blob: String) {
        splitFeatBlob(blob).forEach { name ->
            if (out.none { it.name.equals(name, ignoreCase = true) }) {
                out += ArtistCredit(name = name, role = ArtistRole.FEATURED, position = out.size)
            }
        }
    }
    addPrimary(parts[0])
    parts.getOrNull(1)?.let { addFeatured(it) }
    parenFeats.forEach { addFeatured(it) }
    return out
}

fun splitFeatBlob(blob: String): List<String> {
    var t = blob.trim()
    t = t.replace(Regex("""[\(\[\{].*$"""), "").trim()
    return t.split(FEAT_BLOB_SPLIT)
        .map { it.trim().trim('.', '-', '/') }
        .filter { it.length >= 2 }
        .distinctBy { it.lowercase() }
}

fun featuredFromText(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    val found = LinkedHashSet<String>()
    FEAT_IN_PARENS.findAll(text).forEach { found += splitFeatBlob(it.groupValues[1]) }
    FEAT_SUFFIX.find(text)?.let { found += splitFeatBlob(it.groupValues[1]) }
    return found.toList()
}

/**
 * Full credit list for a song: album-artist primaries, extra track artists as
 * featured, plus `feat.` parsed from the title and album title.
 */
fun allCreditsForSong(song: Song): List<ArtistCredit> {
    val albumParsed = parseArtistCreditList(song.albumArtist)
    val artistParsed = parseArtistCreditList(song.artist)
    val titleFeats = featuredFromText(song.title)
    val albumFeats = featuredFromText(song.album)
    val artistFieldFeats = featuredFromText(song.artist) + featuredFromText(song.albumArtist)

    val primaries = LinkedHashSet<String>()
    albumParsed.filter { it.role == ArtistRole.PRIMARY }.forEach { primaries += it.name }
    if (primaries.isEmpty()) {
        artistParsed.firstOrNull { it.role == ArtistRole.PRIMARY }?.let { primaries += it.name }
    }
    if (primaries.isEmpty()) {
        primaryArtistName(song.effectiveAlbumArtist)?.let { primaries += it }
    }

    fun isPrimary(name: String) = primaries.any { it.equals(name, ignoreCase = true) }

    val featured = LinkedHashSet<String>()
    fun addFeat(name: String) {
        val n = name.trim()
        if (n.length >= 2 && !isPrimary(n)) featured += n
    }
    albumParsed.filter { it.role == ArtistRole.FEATURED }.forEach { addFeat(it.name) }
    artistParsed.forEach { c ->
        if (c.role == ArtistRole.FEATURED || !isPrimary(c.name)) addFeat(c.name)
    }
    titleFeats.forEach { addFeat(it) }
    albumFeats.forEach { addFeat(it) }
    artistFieldFeats.forEach { addFeat(it) }

    val out = ArrayList<ArtistCredit>(primaries.size + featured.size)
    primaries.forEach { n -> out += ArtistCredit(n, ArtistRole.PRIMARY, out.size) }
    featured.forEach { n -> out += ArtistCredit(n, ArtistRole.FEATURED, out.size) }
    return out
}

fun isCombinedArtistName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    if (name.contains(';')) return true
    if (FEAT_IN_PARENS.containsMatchIn(name)) return true
    if (FEAT_SPLIT.containsMatchIn(name)) return true
    return false
}

/** Names only (primary then featured) — for linking. */
fun parseArtistCredits(raw: String?): List<String> =
    parseArtistCreditList(raw).map { it.name }

fun primaryArtistName(raw: String?): String? =
    parseArtistCreditList(raw).firstOrNull { it.role == ArtistRole.PRIMARY }?.name
        ?: parseArtistCreditList(raw).firstOrNull()?.name

fun featuredArtistNames(raw: String?): List<String> =
    parseArtistCreditList(raw).filter { it.role == ArtistRole.FEATURED }.map { it.name }

enum class ReleaseType {
    SINGLE,
    EP,
    ALBUM,
    COMPILATION,
    OTHER;

    val label: String
        get() = when (this) {
            SINGLE -> "Single"
            EP -> "EP"
            ALBUM -> "Album"
            COMPILATION -> "Compilation"
            OTHER -> "Release"
        }
}

/**
 * Heuristic release classification until MusicBrainz / Jellyfin types land.
 * Track-count based (Spotify-adjacent).
 */
fun guessReleaseType(trackCount: Int): ReleaseType = when {
    trackCount <= 3 -> ReleaseType.SINGLE
    trackCount <= 8 -> ReleaseType.EP
    else -> ReleaseType.ALBUM
}
