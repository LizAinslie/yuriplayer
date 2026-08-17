package capital.yuri.yuriplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_prefs",
    indices = [Index(value = ["albumKey"], unique = true)]
)
data class AlbumPrefsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumKey: String,
    val shuffleDefault: Boolean = false,
    val repeatMode: String = "ALL",
    val pinnedToMyStuff: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "album_metadata",
    indices = [Index(value = ["albumKey"], unique = true)]
)
data class AlbumMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumKey: String,
    val year: Int? = null,
    val mbid: String? = null,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val source: String = "musicbrainz",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val lookupFailed: Boolean = false
)

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

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val customImageUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

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
    /** JSON array of {"label","url","category"}. */
    val linksJson: String? = null,
    /** JSON string array of genre names. */
    val genresJson: String? = null,
    val source: String = "local",
    val updatedAtMs: Long = System.currentTimeMillis()
)

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

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "source_instances")
data class SourceInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val baseUrl: String? = null,
    val username: String? = null,
    val secret: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val extraJson: String? = null
)

@Entity(tableName = "scrobbler_instances")
data class ScrobblerInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val baseUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val enabled: Boolean = false,
    val extraJson: String? = null
)
