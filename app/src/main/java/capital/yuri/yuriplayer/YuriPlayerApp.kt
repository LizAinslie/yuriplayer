package capital.yuri.yuriplayer

import android.app.Application
import android.os.Handler
import android.os.Looper
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.di.appModule
import capital.yuri.yuriplayer.network.httpModule
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class YuriPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@YuriPlayerApp)
            modules(httpModule, appModule)
        }

        get<LibrarySettings>().migrateLegacyNetworkConsentIfNeeded()

        // Defer library bootstrap so Activity can bind MusicService and restore
        // the last track before any MediaStore / Room bulk work starts.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { get<LibraryIndex>().bootstrap() }
        }, LIBRARY_BOOTSTRAP_DELAY_MS)
    }

    companion object {
        private const val LIBRARY_BOOTSTRAP_DELAY_MS = 1_200L
    }
}
