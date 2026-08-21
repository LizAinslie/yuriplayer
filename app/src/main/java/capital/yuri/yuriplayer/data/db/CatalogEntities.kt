package capital.yuri.yuriplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Built-in source type strings. Remote instances also live in [SourceInstanceEntity];
 * local files use [SOURCE_TYPE_LOCAL] with [sourceInstanceId] = null.
 */
object CatalogSources {
    const val LOCAL = "LOCAL"
    const val JELLYFIN = "JELLYFIN"
    const val SUBSONIC = "SUBSONIC"
    /** Legacy alias — treat as [SUBSONIC] protocol. */
    const val NAVIDROME = "NAVIDROME"
    const val WEBDAV = "WEBDAV"
    const val MUSICBRAINZ = "MUSICBRAINZ" // enrichment-only, not a playable source
}

/**
 * One playable track from one source.
 *
 * Explore unions rows across sources; playback picks a row (local preferred
 * unless a [SourceOverrideEntity] says otherwise). My Stuff / playlists
 * reference [songKey].
 */
@Entity(
    tableName = "catalog_tracks",
    indices = [
        Index(value = ["songKey"], unique = true),
        Index(value = ["sourceType", "sourceInstanceId"]),
        Index(value = ["albumKey"]),
        Index(value = ["artistKey"]),
        Index(value = ["title"]),
        Index(value = ["lastSeenAtMs"])
    ]
)
data class CatalogTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable identity: lowercase path, or content URI, or "source:externalId". */
    val songKey: String,
    /** LOCAL | JELLYFIN | SUBSONIC | … */
    val sourceType: String,
    /** FK-ish to source_instances.id; null for built-in local scanner. */
    val sourceInstanceId: Long? = null,
    /** Remote library id when source is not local. */
    val externalId: String? = null,

    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,

    /** Normalized keys for joining albums / artists in Explore. */
    val albumKey: String? = null,
    val artistKey: String? = null,

    val contentUri: String,
    val path: String? = null,
    val albumArtUri: String? = null,
    val mimeType: String? = null,

    val isTagged: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** Bumped each successful sync; rows not seen can be pruned for that source. */
    val lastSeenAtMs: Long = System.currentTimeMillis()
)

/**
 * Normalized album identity shared across sources.
 * Tracks point here via [CatalogTrackEntity.albumKey].
 * Year / cover may be filled by MusicBrainz enrichment or a remote library.
 */
@Entity(
    tableName = "catalog_albums",
    indices = [
        Index(value = ["albumKey"], unique = true),
        Index(value = ["artistKey"]),
        Index(value = ["year"])
    ]
)
data class CatalogAlbumEntity(
    @PrimaryKey val albumKey: String,
    val name: String? = null,
    val artist: String? = null,
    val artistKey: String? = null,
    val year: Int? = null,
    val trackCount: Int = 0,
    val releaseType: String? = null, // SINGLE | EP | ALBUM | …
    val mbid: String? = null,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    /** Which source last contributed structural fields (name/artist/count). */
    val primarySourceType: String = CatalogSources.LOCAL,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Normalized artist identity (one Explore page per name).
 * Profile fields may come from local tags, MusicBrainz, Jellyfin, etc.
 */
@Entity(
    tableName = "catalog_artists",
    indices = [Index(value = ["artistKey"], unique = true)]
)
data class CatalogArtistEntity(
    @PrimaryKey val artistKey: String,
    val displayName: String,
    val trackCount: Int = 0,
    val albumCount: Int = 0,
    val bio: String? = null,
    val imageUri: String? = null,
    val websiteUrl: String? = null,
    val linksJson: String? = null,
    val mbid: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

enum class CreditSubject {
    TRACK,
    ALBUM
}

/**
 * Primary vs featured credits for a track or album.
 * Album cards / explore show [ArtistRole.PRIMARY] only.
 */
@Entity(
    tableName = "catalog_credits",
    indices = [
        Index(value = ["subjectType", "subjectKey"]),
        Index(value = ["artistKey"])
    ]
)
data class CatalogCreditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TRACK | ALBUM */
    val subjectType: String,
    /** songKey or albumKey */
    val subjectKey: String,
    val artistKey: String,
    val displayName: String,
    /** PRIMARY | FEATURED */
    val role: String,
    val position: Int = 0
)
