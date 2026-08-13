package capital.yuri.yuriplayer.di

import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.LibraryCache
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MusicRepository
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.player.PlaybackHistoryStore
import capital.yuri.yuriplayer.player.PlaybackStateStore
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { LibrarySettings(androidContext()) }
    single { LibraryCache(androidContext()) }
    single { MusicRepository(androidContext(), get()) }
    single { LibraryIndex(get(), get()) }

    single { AlbumArtCache() }
    single { PlayerThemeStore(get()) }
    single { QueueManager() }
    single { PlaybackStateStore(androidContext()) }
    single { PlaybackHistoryStore(androidContext()) }
    single { PlayerController(androidContext()) }
}
