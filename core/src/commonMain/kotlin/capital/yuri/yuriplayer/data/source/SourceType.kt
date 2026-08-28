package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.Song

enum class SourceType(val rank: Int) {
    LOCAL(0),
    JELLYFIN(10),
    /** OpenSubsonic / Subsonic protocol (Navidrome, Gonic, Official, …). */
    SUBSONIC(20),
    /** @deprecated Prefer [SUBSONIC]; kept for existing source_instances rows. */
    NAVIDROME(20),
    WEBDAV(30),
    OTHER(100);

    fun displayName(): String = when (this) {
        LOCAL -> "Local"
        JELLYFIN -> "Jellyfin"
        SUBSONIC -> "Subsonic"
        NAVIDROME -> "Navidrome"
        WEBDAV -> "WebDAV"
        OTHER -> "Other"
    }

    companion object {
        fun from(raw: String?): SourceType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER

        /** True for Subsonic-protocol backends including legacy NAVIDROME rows. */
        fun isSubsonicFamily(type: SourceType): Boolean =
            type == SUBSONIC || type == NAVIDROME
    }
}

data class SourceOffering(
    val sourceType: SourceType,
    val sourceId: Long? = null,
    val sourceName: String,
    val song: Song
)
