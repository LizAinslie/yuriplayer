package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.dialog.InWindowPanel
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.desktop.DesktopPlaylistStore

@Composable
fun AddToPlaylistDialog(
    tracks: List<Track>,
    store: DesktopPlaylistStore,
    library: List<Track>,
    onDismiss: () -> Unit
) {
    val playlists by store.playlists.collectAsState()
    val ids = remember(tracks) { tracks.map { it.id } }
    var initiallyIn by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(ids) {
        val containing = if (ids.size == 1) {
            store.playlistsContaining(ids.first())
        } else {
            playlists.filter { pl -> ids.all { it in pl.trackIds } }.map { it.id }.toSet()
        }
        initiallyIn = containing
        selected = containing
    }

    val visible = remember(playlists, query, selected) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) playlists
        else playlists.filter { it.name.contains(q, ignoreCase = true) }
        filtered.sortedWith(
            compareByDescending<capital.yuri.yuriplayer.desktop.DesktopPlaylist> { it.id in selected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    InWindowPanel(onDismiss = onDismiss, modifier = Modifier.width(420.dp).height(560.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Add to playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (tracks.size == 1) tracks.first().displayTitle else "${tracks.size} tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            if (creating) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { creating = false; newName = "" }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val pl = store.create(newName, trackIds = ids)
                            selected = selected + pl.id
                            initiallyIn = initiallyIn + pl.id
                            creating = false
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Create") }
                }
            } else {
                OutlinedButton(
                    onClick = { creating = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New playlist")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text("Find a playlist") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )
            if (visible.isEmpty()) {
                Text(
                    if (playlists.isEmpty()) "No playlists yet — create one above."
                    else "No playlists match \"$query\".",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            } else {
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    items(visible, key = { it.id }) { pl ->
                        val checked = pl.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - pl.id else selected + pl.id
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (it) selected + pl.id else selected - pl.id
                                }
                            )
                            CoverArt(
                                model = pl.artworkUri(library),
                                size = 40.dp,
                                corner = 6.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${pl.trackIds.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val toAdd = selected - initiallyIn
                    val toRemove = initiallyIn - selected
                    toAdd.forEach { store.addTracks(it, ids) }
                    toRemove.forEach { store.removeTracks(it, ids) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("Done")
            }
        }
    }
}
