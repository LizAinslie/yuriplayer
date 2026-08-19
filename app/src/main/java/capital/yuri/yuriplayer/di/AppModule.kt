package capital.yuri.yuriplayer.di

import capital.yuri.yuriplayer.data.AlbumArtCache
import capital.yuri.yuriplayer.data.ArtistProfileRepository
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.ExploreSearchService
import capital.yuri.yuriplayer.data.LibraryCache
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibraryScanNotifier
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MetadataEditService
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.MusicRepository
import capital.yuri.yuriplayer.data.MyStuffPinStore
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.ScanCheckpointStore
import capital.yuri.yuriplayer.data.UserImageStore
import capital.yuri.yuriplayer.data.db.YuriDatabase
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistInfoSource
import capital.yuri.yuriplayer.data.source.AudioDbArtistImageSource
import capital.yuri.yuriplayer.data.source.BandsintownClient
import capital.yuri.yuriplayer.data.source.DeezerArtistImageSource
import capital.yuri.yuriplayer.data.source.DiscogsArtistImageSource
import capital.yuri.yuriplayer.data.source.JellyfinClient
import capital.yuri.yuriplayer.data.source.LibrarySource
import capital.yuri.yuriplayer.data.source.LibrarySourceFactory
import capital.yuri.yuriplayer.data.source.LibrarySourceRegistry
import capital.yuri.yuriplayer.data.source.LocalArtistProfileProvider
import capital.yuri.yuriplayer.data.source.LocalLibrarySource
import capital.yuri.yuriplayer.data.source.MusicBrainzArtistProfileProvider
import capital.yuri.yuriplayer.data.source.MusicBrainzClient
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceResolver
import capital.yuri.yuriplayer.data.source.SubsonicClient
import capital.yuri.yuriplayer.data.source.WikipediaArtistImageSource
import capital.yuri.yuriplayer.data.source.WikidataArtistImageSource
import capital.yuri.yuriplayer.data.theme.ThemeService
import capital.yuri.yuriplayer.media.FfmpegService
import capital.yuri.yuriplayer.player.MusicServiceAutoPlay
import capital.yuri.yuriplayer.player.PlaybackHistoryStore
import capital.yuri.yuriplayer.player.PlaybackStateStore
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueEventBridge
import capital.yuri.yuriplayer.player.QueueManager
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.RadioPlaybackAlgorithm
import capital.yuri.yuriplayer.player.radio.ReleasePoolAlgorithm
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { LibrarySettings(androidContext()) }
    single { LibraryCache(androidContext()) }
    single { MusicRepository(androidContext(), get()) }
    single { MyStuffPinStore(androidContext()) }
    single { UserImageStore(androidContext()) }
    single { FfmpegService(androidContext()) }
    single { LibraryScanNotifier(androidContext()) }
    single { ScanCheckpointStore(androidContext()) }

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
    single {
        LibraryIndex(
            context = androidContext(),
            repository = get(),
            cache = get(),
            catalog = get(),
            notifier = get()
        )
    }

    single { SourceInstanceRepository(get()) }

    single {
        val context = androidContext()
        createJellyfin {
            clientInfo = ClientInfo(
                name = "YuriPlayer",
                version = "0.1.0"
            )
            deviceInfo = DeviceInfo(
                id = JellyfinClient.stableDeviceId(context),
                name = "Android"
            )
            this.context = context
        }
    }
    single { JellyfinClient(jellyfin = get()) }

    single { SubsonicClient(get<HttpClient>(), get<Json>()) }
    single { LocalLibrarySource(get(), get()) }
    single {
        LibrarySourceFactory(
            local = get(),
            jellyfinClient = get(),
            subsonicClient = get(),
            instances = get()
        )
    }
    single<List<LibrarySource>> { listOf(get<LocalLibrarySource>()) }
    single { LibrarySourceRegistry(get()) }

    single { SourceResolver(get()) }
    single {
        ExploreSearchService(
            context = androidContext(),
            factory = get(),
            library = get(),
            sourceResolver = get(),
            catalog = get(),
            pinStore = get(),
            instances = get(),
            jellyfinClient = get(),
            subsonicClient = get(),
            notifier = get(),
            settings = get(),
            checkpoints = get()
        )
    }

    single { PlaylistRepository(get(), get(), get()) }
    single { MusicBrainzClient(get<HttpClient>()) }
    single { BandsintownClient(get<HttpClient>()) }

    single<List<ArtistInfoSource>> {
        listOf(
            MusicBrainzArtistProfileProvider(androidContext(), get()),
            WikipediaArtistImageSource(get()),
            WikidataArtistImageSource(get()),
            DeezerArtistImageSource(get()),
            AudioDbArtistImageSource(get()),
            DiscogsArtistImageSource(get())
        )
    }
    single { ArtistInfoService(get(), get()) }

    single {
        ArtistProfileRepository(
            dao = get(),
            providers = listOf(
                LocalArtistProfileProvider(),
                MusicBrainzArtistProfileProvider(androidContext(), get())
            ),
            images = get(),
            artistInfo = get()
        )
    }

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

    single { MetadataEditService(androidContext(), get(), get()) }

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
