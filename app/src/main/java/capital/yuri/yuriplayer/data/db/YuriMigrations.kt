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
                    id || '-legacy',
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

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE catalog_artists ADD COLUMN mbid TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_credits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subjectType TEXT NOT NULL,
                    subjectKey TEXT NOT NULL,
                    artistKey TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    role TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_catalog_credits_subjectType_subjectKey ON catalog_credits(subjectType, subjectKey)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_catalog_credits_artistKey ON catalog_credits(artistKey)"
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_catalog_tracks_sourceType_sourceInstanceId_externalId " +
                    "ON catalog_tracks(sourceType, sourceInstanceId, externalId)"
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS artist_aliases (
                    aliasKey TEXT NOT NULL PRIMARY KEY,
                    canonicalKey TEXT NOT NULL,
                    aliasName TEXT NOT NULL,
                    source TEXT NOT NULL,
                    createdAtMs INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_artist_aliases_canonicalKey ON artist_aliases(canonicalKey)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
}
