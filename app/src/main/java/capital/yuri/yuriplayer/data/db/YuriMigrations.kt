package capital.yuri.yuriplayer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive Room migrations. Register every new Migration here and bump
 * [YuriDatabase] version — never use fallbackToDestructiveMigration.
 *
 * Example when adding a column:
 * ```
 * val MIGRATION_6_7 = object : Migration(6, 7) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE artist_profiles ADD COLUMN foo TEXT")
 *     }
 * }
 * ```
 * then include it in [ALL].
 */
object YuriMigrations {

    /** Currently no pending migrations (schema is at version 6). */
    val ALL: Array<Migration> = emptyArray()

    // Keep historical migrations listed for reference / future use:
    //
    // val MIGRATION_5_6 = object : Migration(5, 6) {
    //     override fun migrate(db: SupportSQLiteDatabase) {
    //         // genresJson was added under destructive migration previously;
    //         // new installs already have the column from the entity definition.
    //     }
    // }
}
