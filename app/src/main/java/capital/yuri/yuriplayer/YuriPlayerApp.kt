package capital.yuri.yuriplayer

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.di.appModule
import capital.yuri.yuriplayer.network.httpModule
import capital.yuri.yuriplayer.player.MusicService
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

        // Start playback service ASAP so last-track restore (metadata + local
        // prepare, or deferred remote) wins the CPU before any library work.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MusicService::class.java)
            )
        }

        // Defer library bootstrap so Activity can bind and the user can hit Play
        // before any MediaStore / Room bulk work starts.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { get<LibraryIndex>().bootstrap() }
        }, LIBRARY_BOOTSTRAP_DELAY_MS)
    }

    companion object {
        private const val LIBRARY_BOOTSTRAP_DELAY_MS = 1_200L
    }
}
