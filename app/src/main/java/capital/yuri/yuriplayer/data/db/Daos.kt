package capital.yuri.yuriplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumPrefsDao {
    @Query("SELECT * FROM album_prefs WHERE albumKey = :key LIMIT 1")
    suspend fun get(key: String): AlbumPrefsEntity?

    @Query("SELECT * FROM album_prefs WHERE albumKey = :key LIMIT 1")
    fun observe(key: String): Flow<AlbumPrefsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlbumPrefsEntity): Long

    @Query("SELECT * FROM album_prefs WHERE pinnedToMyStuff = 1")
    fun observePinned(): Flow<List<AlbumPrefsEntity>>
}

@Dao
interface PlaylistPrefsDao {
    @Query("SELECT * FROM playlist_prefs WHERE playlistId = :id LIMIT 1")
    suspend fun get(id: String): PlaylistPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistPrefsEntity): Long
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeTracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getTracks(playlistId: String): List<PlaylistTrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND position = :position")
    suspend fun deleteTrackAt(playlistId: String, position: Int)

    @Transaction
    suspend fun replaceTracks(playlistId: String, songKeys: List<String>) {
        clearTracks(playlistId)
        if (songKeys.isEmpty()) return
        insertTracks(
            songKeys.mapIndexed { index, key ->
                PlaylistTrackEntity(playlistId = playlistId, position = index, songKey = key)
            }
        )
    }
}

@Dao
interface ArtistProfileDao {
    @Query("SELECT * FROM artist_profiles WHERE artistKey = :key LIMIT 1")
    suspend fun get(key: String): ArtistProfileEntity?

    @Query("SELECT * FROM artist_profiles WHERE artistKey = :key LIMIT 1")
    fun observe(key: String): Flow<ArtistProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArtistProfileEntity)

    @Query("DELETE FROM artist_profiles WHERE artistKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface SourceOverrideDao {
    @Query("SELECT * FROM source_overrides WHERE scope = :scope AND scopeKey = :scopeKey LIMIT 1")
    suspend fun get(scope: String, scopeKey: String): SourceOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceOverrideEntity): Long

    @Query("DELETE FROM source_overrides WHERE scope = :scope AND scopeKey = :scopeKey")
    suspend fun delete(scope: String, scopeKey: String)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface SourceInstanceDao {
    @Query("SELECT * FROM source_instances ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SourceInstanceEntity>>

    @Query("SELECT * FROM source_instances ORDER BY sortOrder, id")
    suspend fun getAll(): List<SourceInstanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceInstanceEntity): Long

    @Update
    suspend fun update(entity: SourceInstanceEntity)

    @Query("DELETE FROM source_instances WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScrobblerInstanceDao {
    @Query("SELECT * FROM scrobbler_instances ORDER BY id")
    fun observeAll(): Flow<List<ScrobblerInstanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScrobblerInstanceEntity): Long

    @Query("DELETE FROM scrobbler_instances WHERE id = :id")
    suspend fun delete(id: Long)
}
