package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.organize.LibraryOrganizeService
import capital.yuri.yuriplayer.data.organize.OrganizeLayout
import capital.yuri.yuriplayer.data.organize.PathTemplate
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Edit path templates for one library root (SAF tree URI, or future folder-like
 * remote mount id) and dry-run / apply organize.
 */
@Composable
fun OrganizeLayoutScreen(
    rootKey: String,
    rootLabel: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val organize: LibraryOrganizeService = koinInject()
    val library: LibraryIndex = koinInject()
    val scope = rememberCoroutineScope()

    val initial = remember(rootKey) { organize.layoutFor(rootKey) }
    var albumPattern by remember { mutableStateOf(initial.albumPattern) }
    var singlePattern by remember { mutableStateOf(initial.singlePattern) }
    var collision by remember { mutableStateOf(initial.collision) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var planSummary by remember { mutableStateOf<String?>(null) }
    var planDetails by remember { mutableStateOf<List<String>>(emptyList()) }
    var samplePreview by remember { mutableStateOf<String?>(null) }

    val busy by organize.busy.collectAsState()
    val status by organize.status.collectAsState()
    val librarySongs by library.songs.collectAsState()

    fun currentLayout() = OrganizeLayout(
        rootKey = rootKey,
        albumPattern = albumPattern.trim().ifBlank { OrganizeLayout.DEFAULT_ALBUM },
        singlePattern = singlePattern.trim().ifBlank { OrganizeLayout.DEFAULT_SINGLE },
        collision = collision,
        enabled = enabled
    )

    fun save() {
        organize.saveLayout(currentLayout())
        Toast.makeText(context, "Layout saved", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Organize", onBack = onBack)

        Text(
            text = rootLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        SettingsSectionTitle("Layout")
        SettingsGroup {
            SettingsSwitchRow(
                title = "Enable organize for this root",
                subtitle = "When off, dry-run still works; apply is blocked",
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
        }

        SettingsSectionTitle("Album tracks")
        SettingsGroup {
            OutlinedTextField(
                value = albumPattern,
                onValueChange = { albumPattern = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                label = { Text("Pattern") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }

        SettingsSectionTitle("Singles / no album")
        SettingsGroup {
            OutlinedTextField(
                value = singlePattern,
                onValueChange = { singlePattern = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                label = { Text("Pattern") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }

        SettingsSectionTitle("Tokens")
        SettingsGroup {
            OrganizeLayout.TOKEN_HELP.forEach { (token, desc) ->
                Text(
                    text = "$token  —  $desc",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        SettingsSectionTitle("Collisions")
        SettingsGroup {
            OrganizeLayout.CollisionPolicy.entries.forEach { policy ->
                val label = when (policy) {
                    OrganizeLayout.CollisionPolicy.SKIP -> "Skip existing"
                    OrganizeLayout.CollisionPolicy.SUFFIX -> "Add (2), (3), …"
                    OrganizeLayout.CollisionPolicy.OVERWRITE -> "Overwrite"
                }
                SettingsNavRow(
                    title = label,
                    trailing = if (collision == policy) "Selected" else null,
                    onClick = { collision = policy }
                )
            }
        }

        SettingsSectionTitle("Actions")
        SettingsGroup {
            SettingsNavRow(
                title = "Save layout",
                subtitle = "Store templates for this root",
                icon = Icons.Default.FolderCopy,
                onClick = { save() }
            )
            SettingsNavRow(
                title = "Preview sample",
                subtitle = samplePreview ?: "Expand patterns against first local track",
                icon = Icons.Default.Preview,
                onClick = {
                    val song = librarySongs.firstOrNull()
                    if (song == null) {
                        samplePreview = "No tracks in library"
                        return@SettingsNavRow
                    }
                    val layout = currentLayout()
                    samplePreview = PathTemplate.relativePathFor(layout, song)
                }
            )
            SettingsNavRow(
                title = "Dry-run",
                subtitle = planSummary ?: "Count moves without changing files",
                icon = Icons.Default.Preview,
                onClick = {
                    scope.launch {
                        save()
                        val plan = organize.plan(rootKey, librarySongs)
                        planSummary =
                            "${plan.moveCount} to move · ${plan.skipCount} already ok / skipped"
                        planDetails = plan.moves
                            .filter { !it.alreadyOk && it.skipReason == null }
                            .take(40)
                            .map { "${it.fromLabel} → ${it.toRelative}" }
                        Toast.makeText(context, planSummary, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            SettingsNavRow(
                title = if (busy) "Organizing…" else "Organize now",
                subtitle = status ?: "Apply layout inside this tree only (never leaves the root)",
                icon = Icons.Default.PlayArrow,
                onClick = {
                    if (busy) return@SettingsNavRow
                    scope.launch {
                        save()
                        val result = organize.apply(rootKey, librarySongs)
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        if (planDetails.isNotEmpty()) {
            SettingsSectionTitle("Dry-run preview (first ${planDetails.size})")
            SettingsGroup {
                planDetails.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

fun shortTreeLabel(treeUri: String): String =
    runCatching {
        Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':') ?: treeUri
    }.getOrDefault(treeUri).takeLast(48)
