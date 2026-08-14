package capital.yuri.yuriplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Per-album playback prefs (shuffle default, etc.). */
@Entity(
    tableName = "album_prefs",
    indices = [Index(value = ["albumKey"], unique = true)]
)
data class AlbumPrefsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalized "artist|album" key. */
    val albumKey: String,
    val shuffleDefault: Boolean = false,
    val repeatMode: String = "ALL", // OFF | ONE | ALL
    val pinnedToMyStuff: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Remote / enriched metadata for an album (MusicBrainz, Cover Art Archive, …).
 * Does not rewrite audio files — overlays missing year / art in the library.
 */
@Entity(
    tableName = "album_metadata",
    indices = [Index(value = ["albumKey"], unique = true)]
)
data class AlbumMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalized "artist|album" key (same as album prefs). */
    val albumKey: String,
    val year: Int? = null,
    /** MusicBrainz release MBID when known. */
    val mbid: String? = null,
    /** Absolute path to a downloaded cover image in app storage. */
    val coverPath: String? = null,
    /** Original remote cover URL (optional). */
    val coverUrl: String? = null,
    val source: String = "musicbrainz",
    /** Last successful lookup (or definitive miss). */
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** True when MB was queried and returned nothing useful — avoid hammering. */
    val lookupFailed: Boolean = false
)

/** Per-playlist prefs (shuffle default when playing as cold source). */
@Entity(
    tableName = "playlist_prefs",
    indices = [Index(value = ["playlistId"], unique = true)]
)
data class PlaylistPrefsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val shuffleDefault: Boolean = false,
    val repeatMode: String = "ALL",
    val pinnedToMyStuff: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** User-owned local playlist. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    /** Optional user-uploaded cover; overrides collage / single-art defaults. */
    val customImageUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** Ordered track membership in a playlist (songKey = path or content URI). */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["songKey"])
    ]
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val songKey: String
)

/** Cached / merged artist profile from local + remote providers. */
@Entity(tableName = "artist_profiles")
data class ArtistProfileEntity(
    @PrimaryKey val artistKey: String,
    val displayName: String,
    val bio: String? = null,
    val imageUri: String? = null,
    val websiteUrl: String? = null,
    /** JSON array of {"label":"…","url":"…"}. */
    val linksJson: String? = null,
    val source: String = "local",
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * User override: prefer a specific source for a track / album / playlist.
 * scope = TRACK | ALBUM | PLAYLIST; scopeKey = songKey / albumKey / playlistId.
 */
@Entity(
    tableName = "source_overrides",
    indices = [Index(value = ["scope", "scopeKey"], unique = true)]
)
data class SourceOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val scopeKey: String,
    val preferredSourceId: Long? = null,
    val preferredSourceType: String? = null
)

/** App-level key/value settings. */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** External music source (Jellyfin, Navidrome, local path, …). */
@Entity(tableName = "source_instances")
data class SourceInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // LOCAL | JELLYFIN | NAVIDROME | WEBDAV | …
    val name: String,
    val baseUrl: String? = null,
    val username: String? = null,
    /** Stored opaque; never log. */
    val secret: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val extraJson: String? = null
)

/** Scrobbler endpoints (ListenBrainz, Last.fm, …). */
@Entity(tableName = "scrobbler_instances")
data class ScrobblerInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // LISTENBRAINZ | LASTFM | …
    val name: String,
    val baseUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val enabled: Boolean = false,
    val extraJson: String? = null
)
