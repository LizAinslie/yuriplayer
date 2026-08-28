package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.Song

/**
 * Cross-source identity for a [Song] (local + Jellyfin + Subsonic of the same
 * recording). Supersedes the legacy desktop `Track` identity model — the String
 * id is carried in [Song.songKey]/[Song.path].
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

/**
 * Cross-source identity (local + Jellyfin + Subsonic of the same recording).
 * Playlists store this so a Navidrome search hit resolves to the library row.
 */
fun Song.catalogKey(): String {
    val t = foldSearch(title ?: displayTitle)
    if (t.isEmpty()) return songKey
    val a = foldSearch(albumArtist ?: artist ?: "")
    val al = foldSearch(album ?: "")
    val n = trackNumber ?: 0
    return "k:$t|$a|$al|$n"
}

/**
 * Artist-agnostic key so Navidrome search (`feat. X` on artist) matches
 * a scanned row (album artist only).
 */
fun Song.looseKey(): String {
    val t = normalizeTitle(title ?: displayTitle)
    val al = foldSearch(album ?: "")
    val n = trackNumber ?: 0
    return "p:$t|$al|$n"
}

fun Song.rawSourceId(): String? = when {
    path?.startsWith("subsonic:") == true -> path!!.removePrefix("subsonic:")
    path?.startsWith("jellyfin:") == true -> path!!.removePrefix("jellyfin:")
    path?.startsWith("navidrome:") == true -> path!!.removePrefix("navidrome:")
    else -> null
}

fun Song.indexKeys(): Set<String> = buildSet {
    add(songKey)
    add(catalogKey())
    add(looseKey())
    rawSourceId()?.let { add(it) }
}

fun Song.playlistKeys(): Set<String> = indexKeys()

fun albumGroupKey(song: Song): String {
    val album = foldSearch(song.album ?: song.displayAlbum)
    val artist = foldSearch(song.albumArtist ?: song.artist ?: "")
    return "$album::$artist"
}

fun albumPageIdentity(song: Song): String {
    val disc = song.discNumber ?: 1
    val tn = song.trackNumber
    val title = normalizeTitle(song.title ?: song.displayTitle)
    return if (tn != null && tn > 0 && title.isNotEmpty()) {
        "n:$disc|$tn|$title"
    } else if (title.isNotEmpty()) {
        "t:$title|${foldSearch(song.album ?: "")}"
    } else {
        "id:${song.songKey}"
    }
}

fun Song.sourceRank(): Int {
    val sid = sourceId.orEmpty()
    val uri = contentUri
    return when {
        sid == "local" || uri.startsWith("file:") -> 0
        songKey.startsWith("jellyfin:") || sid.contains("jelly", true) -> 10
        songKey.startsWith("subsonic:") || songKey.startsWith("navidrome:") ||
            sid.contains("navi", true) || sid.contains("subsonic", true) -> 20
        else -> 50
    }
}

data class CollapsedSong(
    val preferred: Song,
    val sources: List<Song>
) {
    val identity: String get() = albumPageIdentity(preferred)
    val multiSource: Boolean get() = sources.size > 1
    val explicit: Boolean get() = sources.any { it.isExplicit }
}

fun collapseAlbumTracks(
    songs: List<Song>,
    preferredIds: Map<String, String> = emptyMap()
): List<CollapsedSong> {
    if (songs.isEmpty()) return emptyList()
    val groups = LinkedHashMap<String, MutableList<Song>>()
    for (s in songs) {
        groups.getOrPut(albumPageIdentity(s)) { mutableListOf() }.add(s)
    }
    return groups.values.map { group ->
        val identity = albumPageIdentity(group.first())
        val prefId = preferredIds[identity]
        val preferred = prefId?.let { id ->
            group.firstOrNull { it.songKey == id || it.sourceId == id }
        } ?: group.minByOrNull { it.sourceRank() } ?: group.first()
        CollapsedSong(preferred, group.distinctBy { it.songKey })
    }.sortedWith(
        compareBy<CollapsedSong> { it.preferred.discNumber ?: 1 }
            .thenBy { it.preferred.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.preferred.displayTitle.lowercase() }
    )
}

fun pickPreferred(sources: List<Song>, preferredId: String?): Song {
    if (sources.isEmpty()) error("empty sources")
    preferredId?.let { id ->
        sources.firstOrNull { it.songKey == id || it.sourceId == id }?.let { return it }
    }
    return sources.minByOrNull { it.sourceRank() } ?: sources.first()
}
