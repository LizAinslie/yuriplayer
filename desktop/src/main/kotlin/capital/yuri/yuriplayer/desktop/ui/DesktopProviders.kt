package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.settings.SettingsGroup
import capital.yuri.yuriplayer.components.settings.SettingsNavRow
import capital.yuri.yuriplayer.components.settings.SettingsNote
import capital.yuri.yuriplayer.components.settings.SettingsSectionTitle
import capital.yuri.yuriplayer.components.settings.SettingsSwitchRow
import capital.yuri.yuriplayer.components.settings.SettingsTopBar
import capital.yuri.yuriplayer.core.source.RemoteAccount
import capital.yuri.yuriplayer.core.source.SourceKind
import capital.yuri.yuriplayer.desktop.DesktopSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ProviderPage {
    data object List : ProviderPage()
    data class Editor(val existing: RemoteAccount?, val kind: SourceKind) : ProviderPage()
}

@Composable
fun DesktopProvidersPane(
    session: DesktopSession,
    onOpenLocal: () -> Unit
) {
    var page by remember { mutableStateOf<ProviderPage>(ProviderPage.List) }
    when (val p = page) {
        ProviderPage.List -> ProvidersList(
            session = session,
            onOpenLocal = onOpenLocal,
            onOpen = { page = ProviderPage.Editor(it, it.kind) },
            onAdd = { page = ProviderPage.Editor(null, it) }
        )
        is ProviderPage.Editor -> ProviderEditor(
            session = session,
            existing = p.existing,
            kind = p.kind,
            onBack = { page = ProviderPage.List }
        )
    }
}

@Composable
private fun ProvidersList(
    session: DesktopSession,
    onOpenLocal: () -> Unit,
    onOpen: (RemoteAccount) -> Unit,
    onAdd: (SourceKind) -> Unit
) {
    val remotes by session.sources.remotes.collectAsState()
    val scanMessage by session.scanMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var listStatus by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsNote("Local library and remote servers YuriPlayer can play from.")
        if (scanMessage.isNotBlank()) {
            Text(
                scanMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        SettingsSectionTitle("On this device")
        SettingsGroup {
            SettingsNavRow(
                title = "Local files",
                subtitle = "Default music folders plus any you add",
                icon = Icons.Default.Folder,
                trailing = "Configure",
                onClick = onOpenLocal
            )
        }

        SettingsSectionTitle("Servers")
        if (remotes.isEmpty()) {
            SettingsGroup {
                SettingsNavRow(
                    title = "No servers yet",
                    subtitle = "Add Jellyfin or Subsonic / OpenSubsonic",
                    icon = Icons.Default.Storage,
                    onClick = { onAdd(SourceKind.JELLYFIN) }
                )
            }
        } else {
            SettingsGroup {
                remotes.forEach { row ->
                    Column {
                        SettingsNavRow(
                            title = row.name.ifBlank { kindLabel(row.kind) },
                            subtitle = buildString {
                                append(kindLabel(row.kind))
                                if (!row.enabled) append(" · off")
                                append(" · ")
                                append(row.baseUrl.removePrefix("https://").removePrefix("http://"))
                            },
                            icon = iconFor(row.kind),
                            trailing = if (row.enabled) "On" else "Off",
                            onClick = { onOpen(row) }
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                enabled = testingId != row.id,
                                onClick = {
                                    testingId = row.id
                                    listStatus = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            session.testConnection(
                                                row.kind,
                                                row.baseUrl,
                                                row.username,
                                                row.secret
                                            )
                                        }
                                        listStatus = result.getOrElse { "Failed: ${it.message ?: it}" }
                                        testingId = null
                                    }
                                }
                            ) {
                                Text(if (testingId == row.id) "Testing…" else "Test connection")
                            }
                        }
                    }
                }
            }
        }

        if (listStatus != null) {
            Text(
                listStatus!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        SettingsSectionTitle("Add")
        SettingsGroup {
            SettingsNavRow(
                title = "Jellyfin",
                subtitle = "Self-hosted media server",
                icon = Icons.Default.Dns,
                onClick = { onAdd(SourceKind.JELLYFIN) }
            )
            SettingsNavRow(
                title = "Subsonic / OpenSubsonic",
                subtitle = "Navidrome, Gonic, Official, …",
                icon = Icons.Default.Cloud,
                onClick = { onAdd(SourceKind.SUBSONIC) }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderEditor(
    session: DesktopSession,
    existing: RemoteAccount?,
    kind: SourceKind,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.name.orEmpty().ifBlank {
        if (kind == SourceKind.JELLYFIN) "Jellyfin" else "Subsonic"
    }) }
    var url by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.secret.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun runTest() {
        if (testing) return
        scope.launch {
            testing = true
            status = null
            val result = withContext(Dispatchers.IO) {
                session.testConnection(kind, url, username, password)
            }
            status = result.getOrElse { "Failed: ${it.message ?: it}" }
            testing = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsTopBar(
            title = if (existing == null) "Add provider" else "Edit provider",
            onBack = onBack
        )
        Text(
            kindLabel(kind),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionTitle("Identity")
        SettingsGroup {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Home server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shown in the providers list and library filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        SettingsSectionTitle("Connection")
        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://music.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { runTest() },
                    enabled = !testing && url.isNotBlank() && username.isNotBlank()
                ) {
                    Text(if (testing) "Testing…" else "Test connection")
                }
            }
        }

        SettingsSectionTitle("Options")
        SettingsGroup {
            SettingsSwitchRow(
                title = "Enabled",
                subtitle = "Include this provider when browsing and playing",
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
        }

        if (status != null) {
            Text(
                status!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        SettingsSectionTitle("Actions")
        SettingsGroup {
            SettingsNavRow(
                title = if (testing) "Testing…" else "Test connection",
                subtitle = "Authenticate against the server",
                onClick = { runTest() }
            )
            SettingsNavRow(
                title = if (saving) "Saving…" else "Save",
                subtitle = "Write label and credentials",
                onClick = {
                    if (saving) return@SettingsNavRow
                    saving = true
                    status = null
                    session.saveRemote(
                        existingId = existing?.id,
                        kind = kind,
                        name = label,
                        baseUrl = url,
                        username = username,
                        password = password,
                        enabled = enabled
                    ) { ok, message ->
                        saving = false
                        if (ok) onBack() else status = message
                    }
                }
            )
            if (existing != null) {
                SettingsNavRow(
                    title = "Delete provider",
                    subtitle = "Remove this server from YuriPlayer",
                    onClick = { confirmDelete = true }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete provider?") },
            text = {
                Text("“${label.ifBlank { kindLabel(kind) }}” will be removed. Local files are unaffected.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.removeRemote(existing.id)
                        confirmDelete = false
                        onBack()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

private fun kindLabel(kind: SourceKind): String = when (kind) {
    SourceKind.JELLYFIN -> "Jellyfin"
    SourceKind.SUBSONIC -> "Subsonic / OpenSubsonic"
    SourceKind.LOCAL -> "Local"
}

private fun iconFor(kind: SourceKind) = when (kind) {
    SourceKind.JELLYFIN -> Icons.Default.Dns
    SourceKind.SUBSONIC -> Icons.Default.Cloud
    SourceKind.LOCAL -> Icons.Default.Folder
}
