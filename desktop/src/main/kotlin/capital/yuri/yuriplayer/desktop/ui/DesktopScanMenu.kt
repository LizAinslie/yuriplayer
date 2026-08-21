package capital.yuri.yuriplayer.desktop.ui

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
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.desktop.DesktopScanStatus
import capital.yuri.yuriplayer.desktop.DesktopSession

@Composable
fun DesktopScanMenu(session: DesktopSession) {
    val scanning by session.isScanning.collectAsState()
    val message by session.scanMessage.collectAsState()
    val sources by session.scanSources.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Box {
        IconButton(onClick = { expanded = true }) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = muted
                )
            } else {
                Icon(
                    Icons.Default.TravelExplore,
                    contentDescription = "Library scan",
                    tint = muted
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = { },
                enabled = false
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (scanning) "Resume / continue scan" else "Scan libraries") },
                onClick = {
                    expanded = false
                    session.requestScan(force = false)
                }
            )
            DropdownMenuItem(
                text = { Text("Force re-scan all") },
                onClick = {
                    expanded = false
                    session.requestScan(force = true)
                }
            )
            DropdownMenuItem(
                text = { Text("Refresh local library only") },
                onClick = {
                    expanded = false
                    session.requestScan(force = true, sourceId = DesktopSession.LOCAL_SCAN_ID)
                }
            )
            if (scanning || sources.any {
                    it.status == DesktopScanStatus.RUNNING ||
                        it.status == DesktopScanStatus.PAUSED
                }
            ) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Pause all") },
                    onClick = {
                        expanded = false
                        session.pauseScan(null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Stop all") },
                    onClick = {
                        expanded = false
                        session.stopScan(null)
                    }
                )
            }
            if (sources.isNotEmpty()) {
                HorizontalDivider()
                sources.forEach { row ->
                    val statusLabel = when (row.status) {
                        DesktopScanStatus.RUNNING -> "running"
                        DesktopScanStatus.PAUSED -> "paused"
                        DesktopScanStatus.STOPPED -> "stopped"
                        DesktopScanStatus.DONE ->
                            if (row.count > 0) "done (${row.count})" else "done"
                        DesktopScanStatus.ERROR -> row.detail.ifBlank { "error" }
                        DesktopScanStatus.IDLE -> "idle"
                    }
                    DropdownMenuItem(
                        text = { Text("${row.name} · $statusLabel") },
                        onClick = { },
                        enabled = false
                    )
                    when (row.status) {
                        DesktopScanStatus.RUNNING -> {
                            DropdownMenuItem(
                                text = { Text("  Pause ${row.name}") },
                                onClick = {
                                    expanded = false
                                    session.pauseScan(row.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("  Stop ${row.name}") },
                                onClick = {
                                    expanded = false
                                    session.stopScan(row.id)
                                }
                            )
                        }
                        DesktopScanStatus.PAUSED, DesktopScanStatus.STOPPED -> {
                            DropdownMenuItem(
                                text = { Text("  Resume ${row.name} only") },
                                onClick = {
                                    expanded = false
                                    session.requestScan(force = false, sourceId = row.id)
                                }
                            )
                        }
                        else -> {
                            DropdownMenuItem(
                                text = { Text("  Scan ${row.name} only") },
                                onClick = {
                                    expanded = false
                                    session.requestScan(force = false, sourceId = row.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("  Force re-scan ${row.name}") },
                                onClick = {
                                    expanded = false
                                    session.requestScan(force = true, sourceId = row.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
