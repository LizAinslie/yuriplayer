package capital.yuri.yuriplayer

import android.app.Application
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.di.appModule
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
            modules(appModule)
        }

        get<LibrarySettings>().migrateLegacyNetworkConsentIfNeeded()

        // Load disk cache immediately; refresh in background if stale.
        // Online metadata is manual by default (album/artist "Fetch additional metadata").
        get<LibraryIndex>().bootstrap()
    }
}
