package capital.yuri.yuriplayer.activities.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.BuildConfig
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibraryScanMode
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.StreamQuality
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.theme.ArtColorVariant
import capital.yuri.yuriplayer.player.engine.PlaybackEngineCatalog
import capital.yuri.yuriplayer.player.engine.PlaybackEngineId
import capital.yuri.yuriplayer.ui.TestTags
import org.koin.compose.koinInject

private sealed class SettingsPage {
    data object Hub : SettingsPage()
    data object Providers : SettingsPage()
    data object LocalLibrary : SettingsPage()
    data object PlaybackEngine : SettingsPage()
    data object StreamingQuality : SettingsPage()
    data object Appearance : SettingsPage()
    data class Organize(
        val rootKey: String,
        val rootLabel: String
    ) : SettingsPage()
    data class ProviderEditor(
        val id: Long?,
        val createType: SourceType?
    ) : SettingsPage()
    data object OpenSourceLicenses : SettingsPage()
    data object VersionInfo : SettingsPage()
}

/**
 * Settings root — Symfonium-style sectioned hub with nested pages for
 * Providers and Local library so source management stays separate.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var stack by remember { mutableStateOf<List<SettingsPage>>(listOf(SettingsPage.Hub)) }
    val page = stack.last()

    fun push(p: SettingsPage) {
        stack = stack + p
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1) else onBack()
    }

    BackHandler { pop() }

    when (val p = page) {
        SettingsPage.Hub -> SettingsHubScreen(
            onBack = onBack,
            onOpenProviders = { push(SettingsPage.Providers) },
            onOpenLocalLibrary = { push(SettingsPage.LocalLibrary) },
            onOpenPlaybackEngine = { push(SettingsPage.PlaybackEngine) },
            onOpenStreamingQuality = { push(SettingsPage.StreamingQuality) },
            onOpenAppearance = { push(SettingsPage.Appearance) },
            onOpenLicenses = { push(SettingsPage.OpenSourceLicenses) },
            onOpenVersion = { push(SettingsPage.VersionInfo) }
        )
        SettingsPage.Providers -> ProvidersSettingsScreen(
            onBack = { pop() },
            onOpenLocal = { push(SettingsPage.LocalLibrary) },
            onOpenProvider = { id -> push(SettingsPage.ProviderEditor(id, null)) },
            onAddProvider = { type -> push(SettingsPage.ProviderEditor(null, type)) }
        )
        SettingsPage.LocalLibrary -> LocalLibrarySettingsScreen(
            onBack = { pop() },
            onOpenOrganize = { rootKey, rootLabel ->
                push(SettingsPage.Organize(rootKey, rootLabel))
            }
        )
        SettingsPage.PlaybackEngine -> PlaybackEngineSettingsScreen(onBack = { pop() })
        SettingsPage.StreamingQuality -> StreamingQualitySettingsScreen(onBack = { pop() })
        SettingsPage.Appearance -> AppearanceSettingsScreen(onBack = { pop() })
        is SettingsPage.Organize -> OrganizeLayoutScreen(
            rootKey = p.rootKey,
            rootLabel = p.rootLabel,
            onBack = { pop() }
        )
        is SettingsPage.ProviderEditor -> ProviderEditorScreen(
            existingId = p.id,
            createType = p.createType,
            onBack = { pop() }
        )
        SettingsPage.OpenSourceLicenses -> OpenSourceLicensesScreen(onBack = { pop() })
        SettingsPage.VersionInfo -> VersionInfoScreen(onBack = { pop() })
    }
}

@Composable
private fun SettingsHubScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenLocalLibrary: () -> Unit,
    onOpenPlaybackEngine: () -> Unit,
    onOpenStreamingQuality: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenVersion: () -> Unit
) {
    val context = LocalContext.current
    val settings: LibrarySettings = koinInject()
    var autoMetadata by remember {
        mutableStateOf(settings.isAutomaticMetadataEnabled())
    }
    var autoPlayRecommended by remember {
        mutableStateOf(settings.isAutoPlayRecommendedEnabled())
    }
    var syncOverMobile by remember {
        mutableStateOf(settings.isSyncOverMobileDataEnabled())
    }
    val scanMode = settings.getScanMode()
    val engineDesc = PlaybackEngineCatalog.descriptor(settings.getPlaybackEngineId())
    val streamQuality = settings.getStreamQuality()
    val coverVariant = settings.getCoverColorVariant()
    val bannerVariant = settings.getBannerColorVariant()
    val versionSubtitle = buildString {
        append(BuildConfig.VERSION_NAME)
        val short = BuildConfig.GIT_COMMIT_SHORT
        if (short != "unknown") {
            append(" · ")
            append(short)
            if (BuildConfig.GIT_DIRTY) append("*")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Settings", onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionTitle("Library")
        SettingsGroup {
            SettingsNavRow(
                title = "Providers",
                subtitle = "Local files, Jellyfin, Subsonic / OpenSubsonic",
                icon = Icons.Default.Storage,
                onClick = onOpenProviders
            )
            SettingsNavRow(
                title = "Local library",
                subtitle = when (scanMode) {
                    LibraryScanMode.MEDIASTORE -> "MediaStore scan"
                    LibraryScanMode.MANUAL -> "Manual folders"
                },
                icon = Icons.Default.Folder,
                onClick = onOpenLocalLibrary
            )
            SettingsSwitchRow(
                title = "Sync over mobile data",
                subtitle = "Allow large remote library indexes on cellular. Off by default to protect your plan.",
                icon = Icons.Default.SignalCellularAlt,
                checked = syncOverMobile,
                onCheckedChange = { enabled ->
                    syncOverMobile = enabled
                    settings.setSyncOverMobileDataEnabled(enabled)
                }
            )
            SettingsNavRow(
                title = "Offline, cache, and download",
                subtitle = "Coming soon",
                icon = Icons.Outlined.CloudDownload,
                onClick = {}
            )
        }

        SettingsSectionTitle("Metadata")
        SettingsGroup {
            SettingsSwitchRow(
                title = "Automatic online metadata",
                subtitle = "Background year & cover lookup via MusicBrainz",
                icon = Icons.Default.Sync,
                checked = autoMetadata,
                onCheckedChange = { enabled ->
                    autoMetadata = enabled
                    settings.setAutomaticMetadataEnabled(enabled)
                }
            )
        }

        SettingsSectionTitle("Playback")
        SettingsGroup {
            SettingsNavRow(
                title = "Playback engine",
                subtitle = engineDesc.displayName,
                icon = Icons.Default.SettingsInputComponent,
                testTag = TestTags.SETTINGS_PLAYBACK_ENGINE,
                onClick = onOpenPlaybackEngine
            )
            SettingsNavRow(
                title = "Streaming quality",
                subtitle = streamQuality.displayName + " — Jellyfin & Subsonic buffer",
                icon = Icons.Default.HighQuality,
                onClick = onOpenStreamingQuality
            )
            SettingsSwitchRow(
                title = "Auto-play recommended",
                subtitle = "When a queue ends, play another album from the same artist",
                icon = Icons.Default.PlayArrow,
                checked = autoPlayRecommended,
                onCheckedChange = { enabled ->
                    autoPlayRecommended = enabled
                    settings.setAutoPlayRecommendedEnabled(enabled)
                }
            )
            SettingsNavRow(
                title = "History size",
                subtitle = "Tracks kept in recently played",
                icon = Icons.Default.History,
                trailing = "50",
                onClick = {}
            )
        }

        SettingsSectionTitle("Interface")
        SettingsGroup {
            SettingsNavRow(
                title = "Appearance",
                subtitle = "Cover · ${coverVariant.displayName}  ·  Banner · ${bannerVariant.displayName}",
                icon = Icons.Default.ColorLens,
                onClick = onOpenAppearance
            )
            SettingsNavRow(
                title = "Library browser",
                subtitle = "Tabs, sort defaults",
                icon = Icons.Default.LibraryMusic,
                onClick = {}
            )
        }

        SettingsSectionTitle("Developer")
        SettingsGroup {
            SettingsNavRow(
                title = "Version",
                subtitle = versionSubtitle,
                icon = Icons.Default.Info,
                onClick = onOpenVersion
            )
            SettingsNavRow(
                title = "Open source licenses",
                subtitle = "Libraries and AGPL application license",
                icon = Icons.Outlined.Gavel,
                onClick = onOpenLicenses
            )
            SettingsNavRow(
                title = "Source repository",
                subtitle = "github.com/LizAinslie/yuriplayer",
                icon = Icons.Default.Code,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(BuildConfig.REPO_URL)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun PlaybackEngineSettingsScreen(onBack: () -> Unit) {
    val settings: LibrarySettings = koinInject()
    var selected by remember { mutableStateOf(settings.getPlaybackEngineId()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Playback engine", onBack = onBack)

        SettingsSectionTitle("Backend")
        TextNote(
            text = "One engine plays everything — local files and remote streams. " +
                "Change applies the next time playback starts (force-stop the app if a track is mid-play)."
        )
        SettingsGroup {
            PlaybackEngineCatalog.available.forEach { desc ->
                val id = PlaybackEngineId.fromId(desc.id)
                val enabled = id != PlaybackEngineId.FFMPEG
                SettingsNavRow(
                    title = desc.displayName,
                    subtitle = desc.description,
                    trailing = when {
                        !enabled -> "Soon"
                        selected == id -> "Selected"
                        else -> null
                    },
                    testTag = "engine_${desc.id}",
                    onClick = {
                        if (!enabled) return@SettingsNavRow
                        settings.setPlaybackEngineId(id)
                        selected = id
                    }
                )
            }
        }

        SettingsSectionTitle("Tips")
        SettingsGroup {
            TextNote(
                text = "LibVLC is the best bet for stubborn FLAC / APE / odd containers. " +
                    "Media3 is lighter and fine for most MP3/AAC and HTTP streams. " +
                    "An FFmpeg AudioTrack engine can share the bundled ffmpeg binary later."
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun StreamingQualitySettingsScreen(onBack: () -> Unit) {
    val settings: LibrarySettings = koinInject()
    var selected by remember { mutableStateOf(settings.getStreamQuality()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Streaming quality", onBack = onBack)

        SettingsSectionTitle("Jellyfin & Subsonic")
        TextNote(
            text = "Quality for playback and the next-track buffer. Original is the " +
                "server file. Lower steps transcode so prefetch uses less data. " +
                "Applies to the next track — the current song keeps playing as-is."
        )
        SettingsGroup {
            StreamQuality.entries.forEach { q ->
                SettingsNavRow(
                    title = q.displayName,
                    subtitle = q.subtitle,
                    trailing = if (selected == q) "Selected" else null,
                    onClick = {
                        settings.setStreamQuality(q)
                        selected = q
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val settings: LibrarySettings = koinInject()
    var coverVariant by remember { mutableStateOf(settings.getCoverColorVariant()) }
    var bannerVariant by remember { mutableStateOf(settings.getBannerColorVariant()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Appearance", onBack = onBack)

        TextNote(
            text = "Dynamic colors come from artwork. Covers (now playing, albums, " +
                "playlists) and artist banners can use different Palette variants. " +
                "Cached per artwork — only re-extracted when the art or these settings change."
        )

        SettingsSectionTitle("Cover")
        SettingsGroup {
            ArtColorVariant.entries.forEach { variant ->
                SettingsNavRow(
                    title = variant.displayName,
                    subtitle = variant.description,
                    trailing = if (coverVariant == variant) "Selected" else null,
                    onClick = {
                        settings.setCoverColorVariant(variant)
                        coverVariant = variant
                    }
                )
            }
        }

        SettingsSectionTitle("Banner")
        SettingsGroup {
            ArtColorVariant.entries.forEach { variant ->
                SettingsNavRow(
                    title = variant.displayName,
                    subtitle = variant.description,
                    trailing = if (bannerVariant == variant) "Selected" else null,
                    onClick = {
                        settings.setBannerColorVariant(variant)
                        bannerVariant = variant
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun LocalLibrarySettingsScreen(
    onBack: () -> Unit,
    onOpenOrganize: (rootKey: String, rootLabel: String) -> Unit
) {
    val context = LocalContext.current
    val settings: LibrarySettings = koinInject()
    val library: LibraryIndex = koinInject()

    var scanMode by remember { mutableStateOf(settings.getScanMode()) }
    var manualTrees by remember { mutableStateOf(settings.getManualTreeUris()) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        settings.addManualTreeUri(uri.toString())
        manualTrees = settings.getManualTreeUris()
        library.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Local library", onBack = onBack)

        SettingsSectionTitle("Scan mode")
        SettingsGroup {
            SettingsNavRow(
                title = "MediaStore",
                subtitle = "System media scanner + folder fill",
                trailing = if (scanMode == LibraryScanMode.MEDIASTORE) "Selected" else null,
                onClick = {
                    settings.setScanMode(LibraryScanMode.MEDIASTORE)
                    scanMode = LibraryScanMode.MEDIASTORE
                    library.refresh()
                }
            )
            SettingsNavRow(
                title = "Manual folders",
                subtitle = "SAF trees + our tag reader only",
                trailing = if (scanMode == LibraryScanMode.MANUAL) "Selected" else null,
                onClick = {
                    settings.setScanMode(LibraryScanMode.MANUAL)
                    scanMode = LibraryScanMode.MANUAL
                    library.refresh()
                }
            )
        }

        if (scanMode == LibraryScanMode.MANUAL) {
            SettingsSectionTitle("Folders")
            SettingsGroup {
                SettingsNavRow(
                    title = "Add folder",
                    subtitle = if (manualTrees.isEmpty()) {
                        "Pick a music directory"
                    } else {
                        "${manualTrees.size} folder(s) granted"
                    },
                    icon = Icons.Default.Folder,
                    onClick = { treePicker.launch(null) }
                )
                manualTrees.forEach { tree ->
                    val label = shortTreeLabel(tree)
                    SettingsNavRow(
                        title = label,
                        subtitle = "Tap to remove",
                        onClick = {
                            settings.removeManualTreeUri(tree)
                            manualTrees = settings.getManualTreeUris()
                            library.refresh()
                        }
                    )
                }
            }

            if (manualTrees.isNotEmpty()) {
                SettingsSectionTitle("Organize")
                SettingsGroup {
                    TextNote(
                        text = "Per-folder path templates. Moves stay inside the granted SAF tree. " +
                            "Same model will apply to remote folder-like sources later."
                    )
                    manualTrees.forEach { tree ->
                        val label = shortTreeLabel(tree)
                        SettingsNavRow(
                            title = "Layout · $label",
                            subtitle = "Album / single patterns · dry-run · apply",
                            icon = Icons.Default.FolderCopy,
                            onClick = { onOpenOrganize(tree, label) }
                        )
                    }
                }
            }
        } else {
            SettingsSectionTitle("Roots")
            SettingsGroup {
                SettingsNavRow(
                    title = "Default roots",
                    subtitle = "Music, Music/library, Download",
                    onClick = {}
                )
            }
            SettingsSectionTitle("Organize")
            SettingsGroup {
                TextNote(
                    text = "Path-template organize requires Manual folders (SAF) so we can " +
                        "rename/move inside a writable tree. Switch scan mode to Manual and " +
                        "grant a folder to configure layouts."
                )
            }
        }

        SettingsSectionTitle("Actions")
        SettingsGroup {
            SettingsNavRow(
                title = "Rescan now",
                subtitle = "Rebuild the local catalog",
                icon = Icons.Default.Sync,
                onClick = { library.refresh() }
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun TextNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

// shortTreeLabel lives in OrganizeLayoutScreen.kt (same package)
