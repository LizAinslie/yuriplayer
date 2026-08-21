package capital.yuri.yuriplayer.core.library

import kotlinx.serialization.Serializable

/**
 * Platform-agnostic library item. [uri] is a file path or http(s) URL the
 * active [capital.yuri.yuriplayer.core.player.PlaybackEngine] can open.
 */
@Serializable
data class Track(
    val id: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val artworkUri: String? = null,
    val path: String? = null,
    /** Local library id `"local"` or a remote account id. */
    val sourceId: String? = null
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Unknown"

    val displayArtist: String
        get() = artist?.takeIf { it.isNotBlank() }
            ?: albumArtist?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"

    val displayAlbum: String
        get() = album?.takeIf { it.isNotBlank() } ?: "Unknown Album"

    /**
     * Cross-source identity (local + Jellyfin + Subsonic of the same recording).
     * Playlists store this so a Navidrome search hit resolves to the library row.
     */
    fun catalogKey(): String {
        val t = foldSearch(title ?: displayTitle)
        if (t.isEmpty()) return id
        val a = foldSearch(albumArtist ?: artist ?: "")
        val al = foldSearch(album ?: "")
        val n = trackNumber ?: 0
        return "k:$t|$a|$al|$n"
    }

    /**
     * Artist-agnostic key so Navidrome search (`feat. X` on artist) matches
     * a scanned row (album artist only).
     */
    fun looseKey(): String {
        val t = normalizeTitle(title ?: displayTitle)
        val al = foldSearch(album ?: "")
        val n = trackNumber ?: 0
        return "p:$t|$al|$n"
    }

    fun rawSourceId(): String? = when {
        id.startsWith("subsonic:") -> id.removePrefix("subsonic:")
        id.startsWith("jellyfin:") -> id.removePrefix("jellyfin:")
        else -> null
    }

    fun indexKeys(): Set<String> = buildSet {
        add(id)
        add(catalogKey())
        add(looseKey())
        rawSourceId()?.let { add(it) }
    }

    fun playlistKeys(): Set<String> = indexKeys()
}
