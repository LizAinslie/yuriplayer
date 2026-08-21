package capital.yuri.yuriplayer.core.library

/**
 * Cross-source collapse for one release. Same rules as mobile album pages:
 * disc + track + normalized title, never squash a whole album onto one row.
 */
private val FEAT_IN_PARENS = Regex(
    """(?i)[\(\[\{]\s*(feat\.?|ft\.?|featuring)\s+[^\)\]\}]+[\)\]\}]"""
)
private val FEAT_SUFFIX = Regex(
    """(?i)\s+(feat\.?|ft\.?|featuring)\s+.+$"""
)

fun normalizeTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    var t = title.trim()
    t = FEAT_IN_PARENS.replace(t, "")
    t = FEAT_SUFFIX.replace(t, "")
    return foldSearch(t)
}

fun albumGroupKey(track: Track): String {
    val album = foldSearch(track.album ?: track.displayAlbum)
    val artist = foldSearch(track.albumArtist ?: track.artist ?: "")
    return "$album::$artist"
}

fun albumPageIdentity(track: Track): String {
    val disc = track.discNumber ?: 1
    val tn = track.trackNumber
    val title = normalizeTitle(track.title ?: track.displayTitle)
    return if (tn != null && tn > 0 && title.isNotEmpty()) {
        "n:$disc|$tn|$title"
    } else if (title.isNotEmpty()) {
        "t:$title|${foldSearch(track.album ?: "")}"
    } else {
        "id:${track.id}"
    }
}

fun Track.sourceRank(): Int {
    val sid = sourceId.orEmpty()
    val uri = uri
    return when {
        sid == "local" || uri.startsWith("file:") -> 0
        id.startsWith("jellyfin:") || sid.contains("jelly", true) -> 10
        id.startsWith("subsonic:") || sid.contains("navi", true) || sid.contains("subsonic", true) -> 20
        else -> 50
    }
}

fun Track.isExplicit(): Boolean {
    val t = title.orEmpty()
    val g = genre.orEmpty()
    if (t.contains("explicit", ignoreCase = true)) return true
    if (g.contains("explicit", ignoreCase = true)) return true
    if (Regex("""(?i)[\(\[]\s*e\s*[\)\]]""").containsMatchIn(t)) return true
    return false
}

data class CollapsedTrack(
    val preferred: Track,
    val sources: List<Track>
) {
    val identity: String get() = albumPageIdentity(preferred)
    val multiSource: Boolean get() = sources.size > 1
    val explicit: Boolean get() = sources.any { it.isExplicit() }
}

fun collapseAlbumTracks(
    tracks: List<Track>,
    preferredIds: Map<String, String> = emptyMap()
): List<CollapsedTrack> {
    if (tracks.isEmpty()) return emptyList()
    val groups = LinkedHashMap<String, MutableList<Track>>()
    for (t in tracks) {
        groups.getOrPut(albumPageIdentity(t)) { mutableListOf() }.add(t)
    }
    return groups.values.map { group ->
        val identity = albumPageIdentity(group.first())
        val prefId = preferredIds[identity]
        val preferred = prefId?.let { id ->
            group.firstOrNull { it.id == id || it.sourceId == id }
        } ?: group.minByOrNull { it.sourceRank() } ?: group.first()
        CollapsedTrack(preferred, group.distinctBy { it.id })
    }.sortedWith(
        compareBy<CollapsedTrack> { it.preferred.discNumber ?: 1 }
            .thenBy { it.preferred.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.preferred.displayTitle.lowercase() }
    )
}

fun pickPreferred(sources: List<Track>, preferredId: String?): Track {
    if (sources.isEmpty()) error("empty sources")
    preferredId?.let { id ->
        sources.firstOrNull { it.id == id || it.sourceId == id }?.let { return it }
    }
    return sources.minByOrNull { it.sourceRank() } ?: sources.first()
}
