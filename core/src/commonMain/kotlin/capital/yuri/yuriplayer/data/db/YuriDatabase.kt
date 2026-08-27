package capital.yuri.yuriplayer.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

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
@ConstructedBy(YuriDatabaseConstructor::class)
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
}

/**
 * KMP Room constructor. The [YuriDatabase] `@Database` class targets non-Android
 * platforms (JVM/desktop) as well as Android, so Room requires this `expect`
 * object. Room's KSP processor generates the matching `actual` (plus the
 * `YuriDatabase_Impl` implementation) for each platform target.
 */
expect object YuriDatabaseConstructor : RoomDatabaseConstructor<YuriDatabase> {
    override fun initialize(): YuriDatabase
}
