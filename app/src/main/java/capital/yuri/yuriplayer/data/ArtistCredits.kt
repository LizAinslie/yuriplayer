package capital.yuri.yuriplayer.data

/**
 * Split a raw artist tag into primary vs featured credits.
 *
 * Separators:
 *  - `;` always splits peers (same role)
 *  - `feat.` / `ft.` / `featuring` starts the featured list
 *
 * Commas and `&` stay inside names ("Earth, Wind & Fire").
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

private val FEAT_SPLIT = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+""")

fun parseArtistCreditList(raw: String?): List<ArtistCredit> {
    if (raw.isNullOrBlank()) return emptyList()
    val parts = raw.split(FEAT_SPLIT, limit = 2)
    val out = ArrayList<ArtistCredit>()
    fun addBlob(blob: String, role: ArtistRole) {
        blob.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            if (out.none { it.name.equals(name, ignoreCase = true) }) {
                out += ArtistCredit(name = name, role = role, position = out.size)
            }
        }
    }
    addBlob(parts[0], ArtistRole.PRIMARY)
    parts.getOrNull(1)?.let { addBlob(it, ArtistRole.FEATURED) }
    return out
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

fun AlbumItem.releaseYear(): Int? = songs.mapNotNull { it.year }.maxOrNull()

fun AlbumItem.releaseType(): ReleaseType = guessReleaseType(trackCount)
