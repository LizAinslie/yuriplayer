package capital.yuri.yuriplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        AlbumPrefsEntity::class,
        AlbumMetadataEntity::class,
        CatalogTrackEntity::class,
        CatalogAlbumEntity::class,
        CatalogArtistEntity::class,
        CatalogCreditEntity::class,
        ArtistAliasEntity::class,
        PlaylistPrefsEntity::class,
        PlaylistEntity::class,
        PlaylistCoverEntity::class,
        PlaylistTrackEntity::class,
        ArtistProfileEntity::class,
        SourceOverrideEntity::class,
        AppSettingEntity::class,
        SourceInstanceEntity::class,
        ScrobblerInstanceEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class YuriDatabase : RoomDatabase() {
    abstract fun albumPrefs(): AlbumPrefsDao
    abstract fun albumMetadata(): AlbumMetadataDao
    abstract fun catalog(): CatalogDao
    abstract fun playlistPrefs(): PlaylistPrefsDao
    abstract fun playlists(): PlaylistDao
    abstract fun artistProfiles(): ArtistProfileDao
    abstract fun sourceOverrides(): SourceOverrideDao
    abstract fun appSettings(): AppSettingsDao
    abstract fun sources(): SourceInstanceDao
    abstract fun scrobblers(): ScrobblerInstanceDao

    companion object {
        fun create(context: Context): YuriDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YuriDatabase::class.java,
                "yuriplayer.db"
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*YuriMigrations.ALL)
                .build()
    }
}
