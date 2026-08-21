package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.list.TrackRow
import capital.yuri.yuriplayer.components.model.toRow
import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.library.matchesQuery
import capital.yuri.yuriplayer.desktop.DesktopCover
import capital.yuri.yuriplayer.desktop.DesktopPlaylist
import capital.yuri.yuriplayer.desktop.DesktopPlaylistStore
import java.io.File

@Composable
fun PlaylistPage(
    playlist: DesktopPlaylist,
    tracks: List<Track>,
    library: List<Track>,
    currentId: String?,
    store: DesktopPlaylistStore,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onEditTrack: (Track) -> Unit
) {
    var name by remember(playlist.id, playlist.updatedAtMs) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id, playlist.updatedAtMs) {
        mutableStateOf(playlist.description.orEmpty())
    }
    var editing by remember { mutableStateOf(false) }
    var showCovers by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<File?>(null) }
    var cropSecret by remember { mutableStateOf(false) }
    val art = playlist.artworkUri(tracks)

    cropSource?.let { src ->
        ImageCropDialog(
            source = src,
            title = if (cropSecret) "Crop secret cover" else "Crop cover",
            onCancel = { cropSource = null },
            onCropped = { cropped ->
                store.addCover(playlist.id, cropped, isSecret = cropSecret, makeActive = !cropSecret)
                cropSource = null
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { editing = !editing }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit playlist")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showCovers = true }
            ) {
                CoverArt(model = art, modifier = Modifier.fillMaxSize(), corner = 12.dp)
            }
            Column(Modifier.weight(1f).padding(start = 24.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = {
                            store.rename(playlist.id, name, description)
                            editing = false
                        }) { Text("Save") }
                        TextButton(onClick = {
                            name = playlist.name
                            description = playlist.description.orEmpty()
                            editing = false
                        }) { Text("Cancel") }
                    }
                } else {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!playlist.description.isNullOrBlank()) {
                        Text(
                            playlist.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        "${tracks.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                FilledIconButton(
                    onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
                TextButton(onClick = { showAdd = true }) { Text("Add songs") }
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track.toRow(highlighted = track.id == currentId),
                    onClick = { onPlay(tracks, index) },
                    onLongClick = { onEditTrack(track) },
                    showCover = true,
                    showAlbum = true
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (index > 0) store.moveTrack(playlist.id, index, index - 1)
                    }, enabled = index > 0) { Text("Up") }
                    TextButton(onClick = {
                        if (index < tracks.lastIndex) store.moveTrack(playlist.id, index, index + 1)
                    }, enabled = index < tracks.lastIndex) { Text("Down") }
                    IconButton(onClick = { store.removeTracks(playlist.id, listOf(track.id)) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSongsDialog(
            library = library,
            existing = playlist.trackIds.toSet(),
            onDismiss = { showAdd = false },
            onAdd = { ids ->
                store.addTracks(playlist.id, ids)
                showAdd = false
            }
        )
    }

    if (showCovers) {
        CoverManagerDialog(
            playlist = playlist,
            store = store,
            onDismiss = { showCovers = false },
            onPick = { secret ->
                val file = DesktopFiles.pickImage(if (secret) "Secret cover" else "Playlist cover")
                if (file != null) {
                    cropSecret = secret
                    cropSource = file
                    showCovers = false
                }
            }
        )
    }
}

@Composable
private fun AddSongsDialog(
    library: List<Track>,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hits = remember(query, library) {
        val q = query.trim()
        if (q.isEmpty()) library.take(40)
        else library.filter { it.matchesQuery(q) }.take(40)
    }
    DialogWindow(onCloseRequest = onDismiss, title = "Add songs") {
        Surface(Modifier.size(520.dp, 560.dp)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search library") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    itemsIndexed(hits, key = { _, t -> t.id }) { _, track ->
                        val inPl = track.id in existing
                        TrackRow(
                            track = track.toRow(),
                            onClick = { if (!inPl) onAdd(listOf(track.id)) },
                            showCover = true,
                            showAlbum = true
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CoverManagerDialog(
    playlist: DesktopPlaylist,
    store: DesktopPlaylistStore,
    onDismiss: () -> Unit,
    onPick: (secret: Boolean) -> Unit
) {
    DialogWindow(onCloseRequest = onDismiss, title = "Playlist covers") {
        Surface(Modifier.size(560.dp, 420.dp)) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("Covers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Secret covers stay off the public slot after you quit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(playlist.covers) { _, cover ->
                        CoverSlotCard(
                            cover = cover,
                            active = cover.id == playlist.activeCoverId,
                            onActivate = { store.setActiveCover(playlist.id, cover.id) },
                            onSecret = { store.setCoverSecret(playlist.id, cover.id, !cover.isSecret) },
                            onDelete = { store.removeCover(playlist.id, cover.id) }
                        )
                    }
                    item {
                        AddCoverChip("Public") { onPick(false) }
                    }
                    item {
                        AddCoverChip("Secret") { onPick(true) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CoverSlotCard(
    cover: DesktopCover,
    active: Boolean,
    onActivate: () -> Unit,
    onSecret: () -> Unit,
    onDelete: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onActivate)
        ) {
            CoverArt(
                model = File(cover.path).toURI().toString(),
                modifier = Modifier.fillMaxSize(),
                corner = 10.dp
            )
            if (cover.isSecret) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Secret",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                )
            }
            if (active) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = cover.isSecret, onCheckedChange = { onSecret() })
            Text("Secret", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onDelete) { Text("Remove") }
    }
}

@Composable
private fun AddCoverChip(label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    DialogWindow(onCloseRequest = onDismiss, title = "New playlist") {
        Surface(Modifier.size(420.dp, 280.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("New playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onCreate(name, description.takeIf { it.isNotBlank() }) },
                        enabled = name.isNotBlank()
                    ) { Text("Create") }
                }
            }
        }
    }
}
