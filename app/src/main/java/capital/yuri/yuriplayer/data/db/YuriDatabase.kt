package capital.yuri.yuriplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumPrefsEntity::class,
        PlaylistPrefsEntity::class,
        AppSettingEntity::class,
        SourceInstanceEntity::class,
        ScrobblerInstanceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class YuriDatabase : RoomDatabase() {
    abstract fun albumPrefs(): AlbumPrefsDao
    abstract fun playlistPrefs(): PlaylistPrefsDao
    abstract fun appSettings(): AppSettingsDao
    abstract fun sources(): SourceInstanceDao
    abstract fun scrobblers(): ScrobblerInstanceDao

    companion object {
        fun create(context: Context): YuriDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YuriDatabase::class.java,
                "yuriplayer.db"
            ).fallbackToDestructiveMigration().build()
    }
}
