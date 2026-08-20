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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Gavel
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
import capital.yuri.yuriplayer.data.source.SourceType
import org.koin.compose.koinInject

private sealed class SettingsPage {
    data object Hub : SettingsPage()
    data object Providers : SettingsPage()
    data object LocalLibrary : SettingsPage()
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
                subtitle = "Theme, accent, dynamic colors",
                icon = Icons.Default.ColorLens,
                onClick = {}
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
    androidx.compose.material3.Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(
            horizontal = 16.dp,
            vertical = 12.dp
        )
    )
}

// local import for TextNote padding
private fun Modifier.padding(horizontal: androidx.compose.ui.unit.Dp, vertical: androidx.compose.ui.unit.Dp) =
    this.then(
        androidx.compose.foundation.layout.padding(horizontal = horizontal, vertical = vertical)
    )
