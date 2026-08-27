package capital.yuri.yuriplayer.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * JVM/desktop factory for the shared [YuriDatabase] schema using the bundled
 * SQLite driver (no Android Context needed).
 */
fun createYuriDatabase(name: String): YuriDatabase =
    Room.databaseBuilder<YuriDatabase>(
        java.io.File(name).absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*YuriMigrations.ALL)
        .build()
