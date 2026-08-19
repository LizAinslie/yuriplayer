package capital.yuri.yuriplayer.activities.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibraryScanMode
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings scaffold — sources, scrobblers, library paths, appearance.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings: LibrarySettings = koinInject()
    val library: LibraryIndex = koinInject()
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val scope = rememberCoroutineScope()

    var autoMetadata by remember {
        mutableStateOf(settings.isAutomaticMetadataEnabled())
    }
    var autoPlayRecommended by remember {
        mutableStateOf(settings.isAutoPlayRecommendedEnabled())
    }
    var scanMode by remember { mutableStateOf(settings.getScanMode()) }
    var manualTrees by remember { mutableStateOf(settings.getManualTreeUris()) }

    val remoteSources by sourcesRepo.observeAll().collectAsState(initial = emptyList())

    var addDialog by remember { mutableStateOf<SourceType?>(null) }

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
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection("Library")
        SettingsRow(
            title = "Local scan mode",
            subtitle = when (scanMode) {
                LibraryScanMode.MEDIASTORE ->
                    "MediaStore — system scanner + folder fill"
                LibraryScanMode.MANUAL ->
                    "Manual folders — SAF trees + our tag reader"
            }
        ) {
            val next = if (scanMode == LibraryScanMode.MEDIASTORE) {
                LibraryScanMode.MANUAL
            } else {
                LibraryScanMode.MEDIASTORE
            }
            settings.setScanMode(next)
            scanMode = next
            library.refresh()
        }

        if (scanMode == LibraryScanMode.MANUAL) {
            SettingsRow(
                title = "Add folder",
                subtitle = if (manualTrees.isEmpty()) {
                    "No folders yet — pick a music directory"
                } else {
                    "${manualTrees.size} folder(s) granted"
                }
            ) {
                treePicker.launch(null)
            }
            manualTrees.forEach { tree ->
                SettingsRow(
                    title = tree.substringAfterLast('%').ifBlank { tree }.takeLast(48),
                    subtitle = "Tap to remove"
                ) {
                    settings.removeManualTreeUri(tree)
                    manualTrees = settings.getManualTreeUris()
                    library.refresh()
                }
            }
        } else {
            SettingsRow("Scan folders", "Default Music / Download roots") {}
        }

        SettingsRow("Rescan library", "Run a full local index now") {
            library.refresh()
        }

        SettingsSection("Metadata")
        SettingsSwitchRow(
            title = "Automatic online metadata",
            subtitle = "Background year & cover lookup via MusicBrainz. " +
                "Off by default — use “Fetch additional metadata” on album/artist pages instead.",
            checked = autoMetadata,
            onCheckedChange = { enabled ->
                autoMetadata = enabled
                settings.setAutomaticMetadataEnabled(enabled)
            }
        )

        SettingsSection("Music sources")
        SettingsRow("Local files", "Always on · mode: ${scanMode.name.lowercase()}") {}
        remoteSources.forEach { row ->
            SettingsRow(
                title = row.name,
                subtitle = buildString {
                    append(row.type)
                    if (!row.enabled) append(" · disabled")
                    row.baseUrl?.let { append(" · ").append(it) }
                }
            ) {
                scope.launch {
                    sourcesRepo.setEnabled(row.id, !row.enabled)
                }
            }
        }
        SettingsRow("Add Jellyfin server", "URL + user + password") {
            addDialog = SourceType.JELLYFIN
        }
        SettingsRow("Add Subsonic / OpenSubsonic", "Navidrome, Gonic, …") {
            addDialog = SourceType.SUBSONIC
        }

        SettingsSection("Scrobbling")
        SettingsRow("ListenBrainz", "Off") {}
        SettingsRow("Last.fm", "Off") {}

        SettingsSection("Playback")
        SettingsSwitchRow(
            title = "Auto-play recommended",
            subtitle = "When a queue ends and Repeat is off, play another random " +
                "album/single from the same artist (skips the album that just " +
                "finished and the one before it).",
            checked = autoPlayRecommended,
            onCheckedChange = { enabled ->
                autoPlayRecommended = enabled
                settings.setAutoPlayRecommendedEnabled(enabled)
            }
        )
        SettingsRow("History size", "50") {}
        SettingsRow("Activity title format", "YuriPlayer: {title} by {artist}") {}

        SettingsSection("Appearance")
        SettingsRow("Accent color", "Purple (default)") {}
        SettingsRow("Theme", "Dark") {}

        Spacer(modifier = Modifier.height(48.dp))
    }

    val dialogType = addDialog
    if (dialogType != null) {
        AddServerDialog(
            type = dialogType,
            onDismiss = { addDialog = null },
            onSave = { name, url, user, secret ->
                scope.launch {
                    when (dialogType) {
                        SourceType.JELLYFIN ->
                            sourcesRepo.addJellyfin(name, url, user, secret)
                        SourceType.SUBSONIC, SourceType.NAVIDROME ->
                            sourcesRepo.addSubsonic(name, url, user, secret)
                        else -> Unit
                    }
                    addDialog = null
                }
            }
        )
    }
}

@Composable
private fun AddServerDialog(
    type: SourceType,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, user: String, secret: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val title = when (type) {
        SourceType.JELLYFIN -> "Add Jellyfin"
        else -> "Add Subsonic / OpenSubsonic"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, url, user, secret) },
                enabled = url.isNotBlank() && user.isNotBlank() && secret.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}
