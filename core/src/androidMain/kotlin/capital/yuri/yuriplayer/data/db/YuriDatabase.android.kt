package capital.yuri.yuriplayer.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Android factory for the shared [YuriDatabase] schema.
 */
fun createYuriDatabase(context: Context): YuriDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        YuriDatabase::class.java,
        "yuriplayer.db"
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*YuriMigrations.ALL)
        .build()
