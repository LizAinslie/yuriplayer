package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import capital.yuri.yuriplayer.data.source.JellyfinClient
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Dedicated providers hub: local entry + remote instances.
 * Add / edit / enable / delete with custom labels.
 */
@Composable
fun ProvidersSettingsScreen(
    onBack: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenProvider: (Long) -> Unit,
    onAddProvider: (SourceType) -> Unit
) {
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val remote by sourcesRepo.observeAll().collectAsState(initial = emptyList())
    var addMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(
            title = "Providers",
            onBack = onBack,
            actions = {
                IconButton(onClick = { addMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add provider")
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Jellyfin") },
                        onClick = {
                            addMenu = false
                            onAddProvider(SourceType.JELLYFIN)
                        },
                        leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Subsonic / OpenSubsonic") },
                        onClick = {
                            addMenu = false
                            onAddProvider(SourceType.SUBSONIC)
                        },
                        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
                    )
                }
            }
        )

        Text(
            "Local library and remote servers YuriPlayer can play from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingsSectionTitle("On this device")
        SettingsGroup {
            SettingsNavRow(
                title = "Local files",
                subtitle = "MediaStore or manual folders",
                icon = Icons.Default.Folder,
                trailing = "Configure",
                onClick = onOpenLocal
            )
        }

        SettingsSectionTitle("Servers")
        if (remote.isEmpty()) {
            SettingsGroup {
                SettingsNavRow(
                    title = "No servers yet",
                    subtitle = "Add Jellyfin or Subsonic / OpenSubsonic",
                    icon = Icons.Default.Storage,
                    onClick = { addMenu = true }
                )
            }
        } else {
            SettingsGroup {
                remote.forEach { row ->
                    SettingsNavRow(
                        title = row.name.ifBlank { typeLabel(row.type) },
                        subtitle = buildString {
                            append(typeLabel(row.type))
                            if (!row.enabled) append(" · off")
                            row.baseUrl?.let { append(" · ").append(it.removePrefix("https://").removePrefix("http://")) }
                        },
                        icon = iconForType(row.type),
                        trailing = if (row.enabled) "On" else "Off",
                        onClick = { onOpenProvider(row.id) }
                    )
                }
            }
        }

        SettingsSectionTitle("Add")
        SettingsGroup {
            SettingsNavRow(
                title = "Jellyfin",
                subtitle = "Self-hosted media server",
                icon = Icons.Default.Dns,
                onClick = { onAddProvider(SourceType.JELLYFIN) }
            )
            SettingsNavRow(
                title = "Subsonic / OpenSubsonic",
                subtitle = "Navidrome, Gonic, Official, …",
                icon = Icons.Default.Cloud,
                onClick = { onAddProvider(SourceType.SUBSONIC) }
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

/**
 * Create or edit a single provider. [existingId] null = create with [createType].
 */
@Composable
fun ProviderEditorScreen(
    existingId: Long?,
    createType: SourceType?,
    onBack: () -> Unit
) {
    val sourcesRepo: SourceInstanceRepository = koinInject()
    val jellyfin: JellyfinClient = koinInject()
    val subsonic: SubsonicClient = koinInject()
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf<SourceInstanceEntity?>(null) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var type by remember {
        mutableStateOf(createType ?: SourceType.SUBSONIC)
    }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            val row = withContext(Dispatchers.IO) { sourcesRepo.get(existingId) }
            if (row != null) {
                loaded = row
                label = row.name
                url = row.baseUrl.orEmpty()
                username = row.username.orEmpty()
                password = row.secret.orEmpty()
                enabled = row.enabled
                type = SourceType.from(row.type)
            }
        } else {
            type = createType ?: SourceType.SUBSONIC
            label = when (type) {
                SourceType.JELLYFIN -> "Jellyfin"
                else -> "Subsonic"
            }
        }
    }

    val title = if (existingId == null) "Add provider" else "Edit provider"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = title, onBack = onBack)

        Text(
            typeLabel(type.name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingsSectionTitle("Identity")
        SettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Home server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Shown in the providers list and library filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        SettingsSectionTitle("Connection")
        SettingsGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://music.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        SettingsSectionTitle("Actions")
        SettingsGroup {
            SettingsNavRow(
                title = if (testing) "Testing…" else "Test connection",
                subtitle = "Authenticate against the server",
                onClick = {
                    if (testing) return@SettingsNavRow
                    scope.launch {
                        testing = true
                        status = null
                        val result = withContext(Dispatchers.IO) {
                            when (type) {
                                SourceType.JELLYFIN ->
                                    jellyfin.authenticate(url, username, password)
                                        .map { "Connected as user ${it.userId.take(8)}…" }
                                else -> {
                                    val session = SubsonicClient.Session(
                                        baseUrl = SourceInstanceRepository.normalizeBaseUrl(url),
                                        username = username,
                                        password = password
                                    )
                                    subsonic.ping(session).map { "Ping ok" }
                                }
                            }
                        }
                        status = result.getOrElse { e -> "Failed: ${e.message ?: e}" }
                        testing = false
                    }
                }
            )
            SettingsNavRow(
                title = "Save",
                subtitle = "Write label and credentials",
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val normalized = SourceInstanceRepository.normalizeBaseUrl(url)
                            val display = label.ifBlank {
                                when (type) {
                                    SourceType.JELLYFIN -> "Jellyfin"
                                    else -> "Subsonic"
                                }
                            }
                            if (existingId == null) {
                                when (type) {
                                    SourceType.JELLYFIN ->
                                        sourcesRepo.addJellyfin(display, normalized, username, password)
                                    else ->
                                        sourcesRepo.addSubsonic(display, normalized, username, password)
                                }
                            } else {
                                val base = loaded ?: sourcesRepo.get(existingId) ?: return@withContext
                                sourcesRepo.upsert(
                                    base.copy(
                                        name = display,
                                        baseUrl = normalized,
                                        username = username,
                                        secret = password,
                                        enabled = enabled,
                                        type = type.name
                                    )
                                )
                            }
                        }
                        onBack()
                    }
                }
            )
            if (existingId != null) {
                SettingsNavRow(
                    title = "Delete provider",
                    subtitle = "Remove this server from YuriPlayer",
                    onClick = { confirmDelete = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }

    if (confirmDelete && existingId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete provider?") },
            text = {
                Text("“${label.ifBlank { typeLabel(type.name) }}” will be removed. Local files are unaffected.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { sourcesRepo.delete(existingId) }
                            confirmDelete = false
                            onBack()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

private fun typeLabel(type: String): String = when (SourceType.from(type)) {
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.SUBSONIC, SourceType.NAVIDROME -> "Subsonic / OpenSubsonic"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.LOCAL -> "Local"
    SourceType.OTHER -> type
}

private fun iconForType(type: String) = when (SourceType.from(type)) {
    SourceType.JELLYFIN -> Icons.Default.Dns
    SourceType.SUBSONIC, SourceType.NAVIDROME -> Icons.Default.Cloud
    else -> Icons.Default.Storage
}
