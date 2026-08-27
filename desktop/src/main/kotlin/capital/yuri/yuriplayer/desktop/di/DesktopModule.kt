package capital.yuri.yuriplayer.desktop.di

import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.player.PlaybackEngine
import capital.yuri.yuriplayer.desktop.DesktopSession
import capital.yuri.yuriplayer.desktop.os.createOsMediaControls
import capital.yuri.yuriplayer.desktop.player.DesktopPlayerHost
import capital.yuri.yuriplayer.desktop.player.VlcjPlaybackEngine
import capital.yuri.yuriplayer.player.MusicServiceAutoPlay
import capital.yuri.yuriplayer.player.QueueManager
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioPlaybackAlgorithm
import capital.yuri.yuriplayer.player.radio.ReleasePoolAlgorithm
import org.koin.dsl.module

/**
 * Desktop DI, mirroring the Android player wiring: the shared core queue
 * objects are singletons here too, and platform seams (engine + OS media
 * controls) are provided per-platform.
 */
val desktopModule = module {
    single<PlaybackEngine> { VlcjPlaybackEngine() }
    single<OsMediaControls> { createOsMediaControls() }

    // Shared queue / radio — same singletons Android uses.
    single { RadioPlaybackAlgorithm() }
    single { ReleasePoolAlgorithm() }
    single {
        RadioEngine(
            catalog = { emptyList() },
            autoPlayEnabled = { false },
            playbackAlgo = get(),
            poolAlgo = get()
        )
    }
    single { MusicServiceAutoPlay(get()) }

    single(createdAtStart = true) {
        val queueManager = QueueManager()
        queueManager.autoPlayHelper = get<MusicServiceAutoPlay>()
        queueManager
    }

    single { DesktopPlayerHost(get(), get()) }
    single { DesktopSession(get(), get()) }
}
