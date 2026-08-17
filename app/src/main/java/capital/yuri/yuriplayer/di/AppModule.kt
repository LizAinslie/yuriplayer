package capital.yuri.yuriplayer.di

import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryCache
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MetadataEditService
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.MusicRepository
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.UserImageStore
import capital.yuri.yuriplayer.data.db.YuriDatabase
import capital.yuri.yuriplayer.data.source.LocalArtistProfileProvider
import capital.yuri.yuriplayer.data.source.MusicBrainzArtistProfileProvider
import capital.yuri.yuriplayer.data.source.MusicBrainzClient
import capital.yuri.yuriplayer.data.source.SourceResolver
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.player.MusicServiceAutoPlay
import capital.yuri.yuriplayer.player.PlaybackHistoryStore
import capital.yuri.yuriplayer.player.PlaybackStateStore
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueEventBridge
import capital.yuri.yuriplayer.player.QueueManager
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioPlaybackAlgorithm
import capital.yuri.yuriplayer.player.radio.ReleasePoolAlgorithm
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { LibrarySettings(androidContext()) }
    single { LibraryCache(androidContext()) }
    single { MusicRepository(androidContext(), get()) }
    single { MyStuffPinStore(androidContext()) }
    single { UserImageStore(androidContext()) }

    single { YuriDatabase.create(androidContext()) }
    single { get<YuriDatabase>().albumPrefs() }
    single { get<YuriDatabase>().albumMetadata() }
    single { get<YuriDatabase>().catalog() }
    single { get<YuriDatabase>().playlistPrefs() }
    single { get<YuriDatabase>().playlists() }
    single { get<YuriDatabase>().artistProfiles() }
    single { get<YuriDatabase>().sourceOverrides() }
    single { get<YuriDatabase>().appSettings() }
    single { get<YuriDatabase>().sources() }
    single { get<YuriDatabase>().scrobblers() }

    single { CatalogRepository(get(), get()) }
    single { LibraryIndex(get(), get(), get()) }

    single { PlaylistRepository(get(), get(), get()) }
    single { MusicBrainzClient() }
    single {
        ArtistProfileRepository(
            dao = get(),
            providers = listOf(
                LocalArtistProfileProvider(),
                MusicBrainzArtistProfileProvider(androidContext(), get())
            ),
            images = get()
        )
    }
    single { SourceResolver(get()) }

    single { AlbumArtCache(androidContext()) }
    single { ThemeService(get()) }
    single { PlayerThemeStore(get(), get()) }

    single {
        MetadataEnrichmentService(
            context = androidContext(),
            dao = get(),
            client = get(),
            library = get(),
            settings = get(),
            artCache = get(),
            themeService = get()
        )
    }

    single { MetadataEditService(androidContext(), get()) }

    single { QueueManager() }

    single { RadioPlaybackAlgorithm() }
    single { ReleasePoolAlgorithm() }
    single {
        RadioEngine(
            library = get(),
            settings = get(),
            playbackAlgo = get(),
            poolAlgo = get()
        )
    }
    single { MusicServiceAutoPlay(get()) }

    single(createdAtStart = true) {
        val qm: QueueManager = get()
        val auto: MusicServiceAutoPlay = get()
        qm.autoPlayHelper = auto
        QueueEventBridge(qm, auto)
    }

    single { PlaybackStateStore(androidContext()) }
    single { PlaybackHistoryStore(androidContext()) }
    single {
        PlayerController(
            context = androidContext(),
            historyStore = get(),
            radioEngine = get(),
            queueManager = get()
        )
    }
}
