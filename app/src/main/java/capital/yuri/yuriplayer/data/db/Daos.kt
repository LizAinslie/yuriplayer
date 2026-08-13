package capital.yuri.yuriplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
