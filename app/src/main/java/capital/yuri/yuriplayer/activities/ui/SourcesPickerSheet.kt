package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.MetadataEditService
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.displayLabel
import capital.yuri.yuriplayer.data.source.supportsEmbeddedTagWrites
import org.koin.compose.koinInject

/**
 * View / prefer sources for a track. Always usable (even with a single source).
 * Streaming servers are labeled read-only; local/cloud file sources as writable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesPickerSheet(
    songTitle: String,
    offerings: List<SourceOffering>,
    preferred: SourceOffering? = offerings.firstOrNull(),
    onDismiss: () -> Unit,
    onPick: (SourceOffering) -> Unit
) {
    val edit: MetadataEditService = koinInject()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            "Sources",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Text(
            songTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(
            "Tap a source to prefer it for playback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (offerings.isEmpty()) {
            Text(
                "No sources indexed for this track yet.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    offerings,
                    key = { "${it.sourceType.name}:${it.sourceId}:${it.song.songKey}" }
                ) { off ->
                    val isPreferred = preferred != null &&
                        preferred.sourceType == off.sourceType &&
                        preferred.sourceId == off.sourceId &&
                        preferred.song.songKey == off.song.songKey
                    val typeWritable = off.sourceType.supportsEmbeddedTagWrites()
                    val writable = typeWritable && edit.isWritableSong(off.song)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(off) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                !typeWritable -> Icons.Default.Lock
                                off.sourceType == SourceType.LOCAL -> Icons.Default.SdStorage
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                off.displayLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isPreferred) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                "${off.sourceType.displayName()} · ${if (writable) "Writable" else "Read-only"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (isPreferred) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Preferred",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        MediaSheetBottomPad()
    }
}
