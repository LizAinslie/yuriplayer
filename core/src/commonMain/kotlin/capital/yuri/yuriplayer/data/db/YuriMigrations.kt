package capital.yuri.yuriplayer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Additive Room migrations. Register every new Migration here and bump
 * [YuriDatabase] version — never use fallbackToDestructiveMigration.
 *
 * Uses the KMP `SQLiteConnection` path so it compiles on all targets
 * (Android + JVM/desktop) with the bundled SQLite driver.
 */
object YuriMigrations {

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) = sql4to5 { connection.execSQL(it) }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) = sql5to6 { connection.execSQL(it) }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) = sql6to7 { connection.execSQL(it) }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) = sql7to8 { connection.execSQL(it) }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) = sql8to9 { connection.execSQL(it) }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) = sql9to10 { connection.execSQL(it) }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10
    )

    private fun sql4to5(exec: (String) -> Unit) {
        exec("ALTER TABLE artist_profiles ADD COLUMN genresJson TEXT")
    }

    private fun sql5to6(exec: (String) -> Unit) {
        exec("ALTER TABLE album_metadata ADD COLUMN genresJson TEXT")
    }

    private fun sql6to7(exec: (String) -> Unit) {
        exec(
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
        exec("CREATE INDEX IF NOT EXISTS index_playlist_covers_playlistId ON playlist_covers(playlistId)")
        exec("CREATE INDEX IF NOT EXISTS index_playlist_covers_playlistId_sortOrder ON playlist_covers(playlistId, sortOrder)")
        exec("ALTER TABLE playlists ADD COLUMN activeCoverId TEXT")
        exec(
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
        exec(
            """
            UPDATE playlists
            SET activeCoverId = id || '-legacy'
            WHERE customImageUri IS NOT NULL AND length(customImageUri) > 0
            """.trimIndent()
        )
    }

    private fun sql7to8(exec: (String) -> Unit) {
        exec("ALTER TABLE catalog_artists ADD COLUMN mbid TEXT")
        exec(
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
        exec("CREATE INDEX IF NOT EXISTS index_catalog_credits_subjectType_subjectKey ON catalog_credits(subjectType, subjectKey)")
        exec("CREATE INDEX IF NOT EXISTS index_catalog_credits_artistKey ON catalog_credits(artistKey)")
    }

    private fun sql8to9(exec: (String) -> Unit) {
        exec(
            "CREATE INDEX IF NOT EXISTS " +
                "index_catalog_tracks_sourceType_sourceInstanceId_externalId " +
                "ON catalog_tracks(sourceType, sourceInstanceId, externalId)"
        )
    }

    private fun sql9to10(exec: (String) -> Unit) {
        exec(
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
        exec("CREATE INDEX IF NOT EXISTS index_artist_aliases_canonicalKey ON artist_aliases(canonicalKey)")
    }
}
