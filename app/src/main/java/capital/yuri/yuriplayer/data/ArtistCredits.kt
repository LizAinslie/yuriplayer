package capital.yuri.yuriplayer.data

/**
 * Split a raw artist tag into discrete credit names for linking.
 *
 * Only hard separators (`;`, `,`) and simple collab marks (`&`, `/`).
 * Does **not** split on `feat.` / `ft.` / `featuring` — those stay as part of
 * the credit string (often mirrored in the title already).
 */
fun parseArtistCredits(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    var s = raw.trim()
    // Normalize & / to semicolon separators (raw string keeps regex escapes valid)
    s = s.replace(Regex("""\s*[&/]\s*"""), ";")
    return s.split(Regex("[,;]"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
}

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
