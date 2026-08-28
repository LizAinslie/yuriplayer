package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.dialog.InWindowPanel
import capital.yuri.yuriplayer.core.source.RemoteAccount
import capital.yuri.yuriplayer.core.source.SourceKind
import capital.yuri.yuriplayer.core.library.LocalLibraryScanner
import capital.yuri.yuriplayer.data.Song

data class SourceChoice(
    val track: Song,
    val label: String,
    val typeLabel: String,
    val writable: Boolean,
    val preferred: Boolean
)

fun sourceChoices(
    sources: List<Song>,
    remotes: List<RemoteAccount>,
    preferredId: String?
): List<SourceChoice> {
    val byId = remotes.associateBy { it.id }
    return sources.map { t ->
        val remote = t.sourceId?.let { byId[it] }
        val local = t.sourceId == LocalLibraryScanner.SOURCE_LOCAL || t.contentUri.startsWith("file:")
        val writable = local
        val typeLabel = when {
            local -> "Local"
            remote?.kind == SourceKind.JELLYFIN -> "Jellyfin"
            remote?.kind == SourceKind.SUBSONIC -> "Navidrome"
            t.songKey.startsWith("jellyfin:") -> "Jellyfin"
            t.songKey.startsWith("subsonic:") || t.songKey.startsWith("navidrome:") -> "Subsonic"
            else -> "Source"
        }
        val label = when {
            local -> "On this device"
            remote != null && remote.name.isNotBlank() -> remote.name
            else -> typeLabel
        }
        SourceChoice(
            track = t,
            label = label,
            typeLabel = typeLabel,
            writable = writable,
            preferred = preferredId != null && (t.songKey == preferredId || t.sourceId == preferredId)
        )
    }
}

@Composable
fun SourcesPickerDialog(
    title: String,
    choices: List<SourceChoice>,
    onDismiss: () -> Unit,
    onPick: (Song) -> Unit
) {
    InWindowPanel(onDismiss = onDismiss, modifier = Modifier.width(420.dp).height(480.dp)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "Tap a source to prefer it for playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(choices, key = { it.track.songKey }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c.track) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                !c.writable -> Icons.Default.Lock
                                c.writable && c.typeLabel == "Local" -> Icons.Default.SdStorage
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.label,
                                fontWeight = if (c.preferred) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                "${c.typeLabel} · ${if (c.writable) "Writable" else "Read-only"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (c.preferred) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Preferred",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}
