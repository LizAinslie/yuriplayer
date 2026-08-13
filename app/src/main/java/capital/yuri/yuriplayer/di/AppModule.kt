package capital.yuri.yuriplayer.di

import capital.yuri.yuriplayer.data.MusicRepository
import capital.yuri.yuriplayer.player.PlayerController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { MusicRepository(androidContext()) }
    single { PlayerController(androidContext()) }
}
