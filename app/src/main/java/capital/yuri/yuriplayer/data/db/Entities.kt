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

/** Per-playlist prefs (future). */
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
