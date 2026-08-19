package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.ExploreSearchService
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.SourceScanStatus
import org.koin.compose.koinInject

/**
 * Top-bar control for library / remote indexing.
 * Icon is [TravelExplore] (scan/discovery) instead of Refresh.
 * Dropdown: scan/resume all, pause/stop all, and per-source pause/stop/resume.
 */
@Composable
fun ExploreScanMenu(
    onForceRescanKey: () -> Unit = {}
) {
    val explore: ExploreSearchService = koinInject()
    val library: LibraryIndex = koinInject()
    val settings: LibrarySettings = koinInject()
    val enrichment: MetadataEnrichmentService = koinInject()
    val context = LocalContext.current

    val scanning by explore.isScanning.collectAsState()
    val checkpoints by explore.sourceCheckpoints.collectAsState()
    val libLoading by library.isLoading.collectAsState()

    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            if (scanning || libLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.TravelExplore,
                    contentDescription = "Library scan",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (scanning) "Resume / continue scan" else "Scan remote libraries") },
                onClick = {
                    expanded = false
                    explore.requestRemoteScan(force = false)
                    Toast.makeText(context, "Scanning in background…", Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text("Force re-scan all") },
                onClick = {
                    expanded = false
                    library.refresh()
                    onForceRescanKey()
                    explore.requestRemoteScan(force = true)
                    if (settings.isNetworkMetadataEnabled()) {
                        enrichment.enrichLibraryAsync()
                    }
                    Toast.makeText(context, "Force re-scan started…", Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text("Refresh local library only") },
                onClick = {
                    expanded = false
                    library.refresh()
                    Toast.makeText(context, "Refreshing local library…", Toast.LENGTH_SHORT).show()
                }
            )

            if (scanning || checkpoints.any {
                    it.status == SourceScanStatus.RUNNING ||
                        it.status == SourceScanStatus.PAUSED
                }
            ) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Pause all") },
                    onClick = {
                        expanded = false
                        explore.pauseScan(null)
                        Toast.makeText(context, "Pausing scan…", Toast.LENGTH_SHORT).show()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Stop all") },
                    onClick = {
                        expanded = false
                        explore.stopScan(null)
                        Toast.makeText(context, "Stopping scan…", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (checkpoints.isNotEmpty()) {
                HorizontalDivider()
                checkpoints.forEach { cp ->
                    val statusLabel = when (cp.status) {
                        SourceScanStatus.RUNNING -> "running"
                        SourceScanStatus.PAUSED -> "paused @ ${cp.delivered}"
                        SourceScanStatus.STOPPED -> "stopped @ ${cp.delivered}"
                        SourceScanStatus.DONE -> "done (${cp.delivered})"
                        SourceScanStatus.IDLE -> "idle"
                    }
                    DropdownMenuItem(
                        text = { Text("${cp.sourceName} · $statusLabel") },
                        onClick = { /* header-ish */ },
                        enabled = false
                    )
                    when (cp.status) {
                        SourceScanStatus.RUNNING -> {
                            DropdownMenuItem(
                                text = { Text("  Pause ${cp.sourceName}") },
                                onClick = {
                                    expanded = false
                                    explore.pauseScan(cp.sourceInstanceId)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("  Stop ${cp.sourceName}") },
                                onClick = {
                                    expanded = false
                                    explore.stopScan(cp.sourceInstanceId)
                                }
                            )
                        }
                        SourceScanStatus.PAUSED, SourceScanStatus.STOPPED -> {
                            DropdownMenuItem(
                                text = { Text("  Resume ${cp.sourceName}") },
                                onClick = {
                                    expanded = false
                                    explore.requestRemoteScan(force = false)
                                    Toast.makeText(
                                        context,
                                        "Resuming ${cp.sourceName}…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        SourceScanStatus.DONE -> {
                            DropdownMenuItem(
                                text = { Text("  Re-scan ${cp.sourceName}") },
                                onClick = {
                                    expanded = false
                                    // Clear done marker by force scan of all for now
                                    explore.requestRemoteScan(force = true)
                                }
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
