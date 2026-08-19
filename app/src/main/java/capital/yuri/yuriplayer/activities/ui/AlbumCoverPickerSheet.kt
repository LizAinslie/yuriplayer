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
import androidx.compose.material.icons.filled.Folder
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
import capital.yuri.yuriplayer.data.CoverCandidate

/**
 * Pick which cover to show for a release. Local options first; selection is
 * stored per albumKey and applied through the art cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumCoverPickerSheet(
    albumTitle: String,
    candidates: List<CoverCandidate>,
    preferredUri: String?,
    onDismiss: () -> Unit,
    onPick: (CoverCandidate) -> Unit,
    onUseDefault: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            "Album cover",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Text(
            albumTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(
            "Local files are preferred by default. Pick a source to override.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onUseDefault)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SdStorage,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Default (prefer local)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (preferredUri.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    "Embedded tags → folder cover → remote",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (preferredUri.isNullOrBlank()) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (candidates.isEmpty()) {
            Text(
                "No alternate covers indexed yet.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(candidates, key = { it.id }) { c ->
                    val selected = preferredUri != null && preferredUri == c.uri
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                c.label.contains("folder", true) -> Icons.Default.Folder
                                c.isLocal -> Icons.Default.SdStorage
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                c.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                if (c.isLocal) "On device" else "Remote",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
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
