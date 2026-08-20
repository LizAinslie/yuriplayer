package capital.yuri.yuriplayer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive Room migrations. Register every new Migration here and bump
 * [YuriDatabase] version — never use fallbackToDestructiveMigration.
 */
object YuriMigrations {

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS playlist_covers (
                    id TEXT NOT NULL PRIMARY KEY,
                    playlistId TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    isSecret INTEGER NOT NULL DEFAULT 0,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    createdAtMs INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playlist_covers_playlistId ON playlist_covers(playlistId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playlist_covers_playlistId_sortOrder ON playlist_covers(playlistId, sortOrder)"
            )
            db.execSQL(
                "ALTER TABLE playlists ADD COLUMN activeCoverId TEXT"
            )
            // Seed cover rows from legacy customImageUri so existing art is kept.
            db.execSQL(
                """
                INSERT INTO playlist_covers (id, playlistId, uri, isSecret, sortOrder, createdAtMs)
                SELECT
                    playlistId || '-legacy',
                    id,
                    customImageUri,
                    0,
                    0,
                    COALESCE(updatedAtMs, createdAtMs, 0)
                FROM playlists
                WHERE customImageUri IS NOT NULL AND length(customImageUri) > 0
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE playlists
                SET activeCoverId = id || '-legacy'
                WHERE customImageUri IS NOT NULL AND length(customImageUri) > 0
                """.trimIndent()
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_6_7)
}
