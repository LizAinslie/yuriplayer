package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import capital.yuri.yuriplayer.components.settings.SettingsCategory
import capital.yuri.yuriplayer.components.settings.SettingsShell
import capital.yuri.yuriplayer.components.theme.AccentPicker
import capital.yuri.yuriplayer.components.theme.ThemeModePicker
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.core.source.SourceKind
import capital.yuri.yuriplayer.desktop.DesktopSession
import javax.swing.JFileChooser

@Composable
fun DesktopSettingsDialog(
    session: DesktopSession,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(SettingsCategory.Appearance) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SettingsShell(
            selected = category,
            onSelect = { category = it },
            onClose = onDismiss,
            modifier = Modifier
                .widthIn(min = 720.dp, max = 1080.dp)
                .heightIn(min = 480.dp, max = 720.dp)
                .fillMaxWidth(0.86f)
                .height(640.dp),
            categories = listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Playback,
                SettingsCategory.Library,
                SettingsCategory.About
            )
        ) {
            when (category) {
                SettingsCategory.Appearance -> AppearancePane(session)
                SettingsCategory.Playback -> PlaybackPane()
                SettingsCategory.Library -> LibraryPane(session)
                SettingsCategory.About -> AboutPane()
            }
        }
    }
}

@Composable
private fun AppearancePane(session: DesktopSession) {
    val choice by session.theme.choice.collectAsState()
    Column {
        Text(
            "Dark, light, or follow the system. Accent is the Material 3 primary. " +
                "Cover art still tints now playing and the play button.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ThemeModePicker(mode = choice.mode, onMode = session.theme::setMode)
        Spacer(Modifier.height(20.dp))
        AccentPicker(selectedId = choice.accentId, onSelect = session.theme::setAccent)
        Spacer(Modifier.height(20.dp))
        Text(
            "Jewel, macOS, and Windows families are next — they won’t depend on Material 3.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PlaybackPane() {
    Column {
        Text("Audio engine", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Desktop uses LibVLC (vlcj). Same family as the default Android engine. " +
                "Media3 is phone-only.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        Text("LibVLC — bundled on Windows/macOS, system VLC on Linux packages.", color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LibraryPane(session: DesktopSession) {
    val folders by session.sources.extraFolders.collectAsState()
    val remotes by session.sources.remotes.collectAsState()
    val status by session.scanMessage.collectAsState()
    val defaults = remember { appDirectories().defaultMusicRoots }
    var jfName by remember { mutableStateOf("") }
    var jfUrl by remember { mutableStateOf("") }
    var jfUser by remember { mutableStateOf("") }
    var jfPass by remember { mutableStateOf("") }
    var ssName by remember { mutableStateOf("") }
    var ssUrl by remember { mutableStateOf("") }
    var ssUser by remember { mutableStateOf("") }
    var ssPass by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Local folders", style = MaterialTheme.typography.titleMedium)
        Text(
            "Default music folders are scanned automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        defaults.forEach { path ->
            Text(path, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        folders.forEach { path ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(path, modifier = Modifier.weight(1f))
                TextButton(onClick = { session.removeFolder(path) }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            pickDirectory()?.let { session.addFolder(it) }
        }) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Text("  Add folder")
        }
        Spacer(Modifier.height(24.dp))
        Text("Servers", style = MaterialTheme.typography.titleMedium)
        Text(
            "Jellyfin and Subsonic / Navidrome / OpenSubsonic — same as on Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        remotes.forEach { remote ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(remote.name.ifBlank { remote.kind.name })
                    Text(
                        "${remote.kind.name.lowercase()} · ${remote.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                TextButton(onClick = { session.removeRemote(remote.id) }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Add Jellyfin", style = MaterialTheme.typography.titleSmall)
        ServerForm(
            name = jfName, onName = { jfName = it },
            url = jfUrl, onUrl = { jfUrl = it },
            user = jfUser, onUser = { jfUser = it },
            password = jfPass, onPassword = { jfPass = it },
            urlHint = "https://jellyfin.example.com"
        )
        Button(
            onClick = {
                session.addJellyfin(jfName, jfUrl, jfUser, jfPass)
                jfPass = ""
            },
            enabled = jfUrl.isNotBlank() && jfUser.isNotBlank() && jfPass.isNotBlank()
        ) { Text("Connect Jellyfin") }
        Spacer(Modifier.height(20.dp))
        Text("Add Subsonic / Navidrome", style = MaterialTheme.typography.titleSmall)
        ServerForm(
            name = ssName, onName = { ssName = it },
            url = ssUrl, onUrl = { ssUrl = it },
            user = ssUser, onUser = { ssUser = it },
            password = ssPass, onPassword = { ssPass = it },
            urlHint = "https://navidrome.example.com"
        )
        Button(
            onClick = {
                session.addSubsonic(ssName, ssUrl, ssUser, ssPass)
                ssPass = ""
            },
            enabled = ssUrl.isNotBlank() && ssUser.isNotBlank() && ssPass.isNotBlank()
        ) { Text("Connect Subsonic") }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { session.rescan() }) { Text("Rescan libraries") }
    }
}

@Composable
private fun ServerForm(
    name: String,
    onName: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    urlHint: String
) {
    var showPass by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedTextField(name, onName, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, onUrl, label = { Text("Server URL") }, placeholder = { Text(urlHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, onUser, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            onPassword,
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPass) "Hide password" else "Show password"
                    )
                }
            }
        )
    }
}

private fun pickDirectory(): String? {
    val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Add music folder"
    chooser.isAcceptAllFileFilterUsed = false
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else null
}

@Composable
private fun AboutPane() {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text("Yuri Player", style = MaterialTheme.typography.headlineSmall)
        Text("Desktop · Compose Multiplatform", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
