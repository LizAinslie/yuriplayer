package capital.yuri.yuriplayer.data

/**
 * Split a raw artist tag into discrete credit names for linking.
 *
 * **Local tags:** only `;` is treated as a multi-artist separator. Commas and
 * ampersands stay inside names ("Earth, Wind & Fire", "Simon & Garfunkel").
 * `feat.` / `ft.` are never split — they belong in the credit / title text.
 *
 * **Structured sources** (Jellyfin, Navidrome, MusicBrainz) should supply an
 * artist array and skip this parser entirely.
 */
fun parseArtistCredits(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';')
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
