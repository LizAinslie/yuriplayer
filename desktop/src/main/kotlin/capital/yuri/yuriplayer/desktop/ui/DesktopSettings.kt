package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import capital.yuri.yuriplayer.components.settings.SettingsCategory
import capital.yuri.yuriplayer.components.settings.SettingsShell

@Composable
fun DesktopSettingsDialog(onDismiss: () -> Unit) {
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
                SettingsCategory.Appearance -> AppearancePane()
                SettingsCategory.Playback -> PlaybackPane()
                SettingsCategory.Library -> LibraryPane()
                SettingsCategory.About -> AboutPane()
            }
        }
    }
}

@Composable
private fun AppearancePane() {
    Column {
        Text(
            "Yuri’s dark stage plus colors from whatever is playing. " +
                "System Material You is an Android thing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Text("Cover art tints the now-playing rail and the play button.", color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlaybackPane() {
    Column {
        Text("Audio engine", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Desktop uses LibVLC (vlcj). That’s the same family as the default " +
                "Android engine. Media3 and FFmpeg are phone-only options.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        Text("LibVLC — bundled on Windows/macOS, system package on Linux.", color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LibraryPane() {
    Column {
        Text(
            "Scans the OS music folder on launch (XDG Music, ~/Music, or %USERPROFILE%\\Music).",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun AboutPane() {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text("Yuri Player", style = MaterialTheme.typography.headlineSmall)
        Text("Desktop · Compose Multiplatform", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
