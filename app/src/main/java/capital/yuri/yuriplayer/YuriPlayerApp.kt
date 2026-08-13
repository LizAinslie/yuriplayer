package capital.yuri.yuriplayer

import android.app.Application
import capital.yuri.yuriplayer.di.appModule
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
    }
}
