package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.SyncInterval
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.effectivePartialInterval
import capital.yuri.yuriplayer.data.source.syncExtras
import capital.yuri.yuriplayer.data.source.withPartialInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun SyncSettingsScreen(onBack: () -> Unit) {
    val settings: LibrarySettings = koinInject()
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val sources by sourcesRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var profileOn by remember { mutableStateOf(settings.isProfileSyncEnabled()) }
    var profileInterval by remember { mutableStateOf(settings.getProfileSyncInterval()) }
    var partialOn by remember { mutableStateOf(settings.isPartialSyncEnabled()) }
    var partialInterval by remember { mutableStateOf(settings.getPartialSyncInterval()) }

    val intervalOptions = SyncInterval.entries
        .filter { it.isActive }
        .map { it.id to it.displayName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Background sync", onBack = onBack)

        SettingsSectionTitle("Account")
        SettingsNote(
            "Playlists you created on Jellyfin or Navidrome land in My Stuff. " +
                "Other people’s playlists stay in Explore."
        )
        SettingsGroup {
            SettingsSwitchRow(
                title = "Refresh my playlists",
                subtitle = "Pull owned playlists from each signed-in server",
                checked = profileOn,
                onCheckedChange = {
                    profileOn = it
                    settings.setProfileSyncEnabled(it)
                }
            )
            if (profileOn) {
                SettingsChoiceRow(
                    title = "Playlist interval",
                    subtitle = "How often to check for new or changed lists",
                    trailing = profileInterval.displayName,
                    options = intervalOptions,
                    onSelect = { id ->
                        val next = SyncInterval.fromId(id)
                        profileInterval = next
                        settings.setProfileSyncInterval(next)
                    }
                )
            }
        }

        SettingsSectionTitle("Libraries")
        SettingsNote(
            "Checks the server song count. If it grew, only the new tracks are pulled. " +
                "A full 40k-track walk happens only when you force a rescan from Explore. " +
                "Libraries are not auto-indexed the first time."
        )
        SettingsGroup {
            SettingsSwitchRow(
                title = "Incremental library sync",
                subtitle = "Keep remote catalogs current in the background",
                checked = partialOn,
                onCheckedChange = {
                    partialOn = it
                    settings.setPartialSyncEnabled(it)
                }
            )
            if (partialOn) {
                SettingsChoiceRow(
                    title = "Default interval",
                    subtitle = "Used by every library unless it has an override",
                    trailing = partialInterval.displayName,
                    options = intervalOptions,
                    onSelect = { id ->
                        val next = SyncInterval.fromId(id)
                        partialInterval = next
                        settings.setPartialSyncInterval(next)
                    }
                )
            }
        }

        if (partialOn && sources.isNotEmpty()) {
            SettingsSectionTitle("Per library")
            SettingsNote("Override the default for a single server. Off skips that library.")
            SettingsGroup {
                val overrideOptions = buildList {
                    add("default" to "Use default (${partialInterval.displayName})")
                    SyncInterval.entries.forEach { add(it.id to it.displayName) }
                }
                sources.filter { it.enabled }.forEach { row ->
                    val extras = row.syncExtras()
                    val override = extras.partialOverride()
                    val effective = row.effectivePartialInterval(partialInterval)
                    val trailing = when {
                        extras.partialIntervalId.isNullOrBlank() -> "Default"
                        override == SyncInterval.OFF -> "Off"
                        else -> effective.displayName
                    }
                    SettingsChoiceRow(
                        title = row.name,
                        subtitle = when {
                            extras.partialIntervalId.isNullOrBlank() ->
                                "Using default · ${effective.displayName}"
                            override == SyncInterval.OFF -> "Skipped"
                            else -> "Override · ${effective.displayName}"
                        },
                        trailing = trailing,
                        options = overrideOptions,
                        onSelect = { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val interval = if (id == "default") null else SyncInterval.fromId(id)
                                    sourcesRepo.upsert(row.withPartialInterval(interval))
                                }
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
