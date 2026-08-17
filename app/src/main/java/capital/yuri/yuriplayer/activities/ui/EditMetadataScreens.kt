package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.MetadataEditService
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongMetadataScreen(
    song: Song,
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    val editor: MetadataEditService = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember(song.songKey) { mutableStateOf(song.title.orEmpty()) }
    var artist by remember(song.songKey) { mutableStateOf(song.artist.orEmpty()) }
    var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var coverMime by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canEdit = remember(song) { editor.isLocalFile(song) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val pair = editor.readImageBytes(uri)
            coverBytes = pair?.first
            coverMime = pair?.second
        }
    }

    fun doSave() {
        saving = true
        error = null
        scope.launch {
            // Song path: write title/artist; if cover set, reuse album writer for one track
            val tagResult = editor.saveSong(
                song,
                MetadataEditService.SongEdit(
                    title = title.ifBlank { null },
                    artist = artist.ifBlank { null }
                )
            )
            if (coverBytes != null && tagResult.failed == 0) {
                val oneTrackAlbum = AlbumItem(
                    name = song.album,
                    artist = song.effectiveAlbumArtist,
                    trackCount = 1,
                    songs = listOf(song)
                )
                editor.saveAlbum(
                    oneTrackAlbum,
                    MetadataEditService.AlbumEdit(
                        albumName = song.album,
                        albumArtist = song.effectiveAlbumArtist,
                        year = song.year,
                        coverBytes = coverBytes,
                        coverMime = coverMime
                    )
                )
            }
            saving = false
            if (tagResult.failed == 0) {
                Toast.makeText(context, tagResult.message, Toast.LENGTH_SHORT).show()
                onSaved()
                onBack()
            } else {
                error = tagResult.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit song") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = canEdit && !saving,
                        onClick = { doSave() }
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!canEdit) {
                Text(
                    "This track is not a writable local file. Metadata edit is only available for local music.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(song = song, size = 96.dp, corner = 8.dp)
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    enabled = canEdit && !saving
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (coverBytes != null) "Cover selected" else "Change cover")
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                enabled = canEdit && !saving
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Artists") },
                supportingText = {
                    Text("Separate multiple artists with a semicolon (;)")
                },
                singleLine = true,
                enabled = canEdit && !saving
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { doSave() },
                enabled = canEdit && !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving…" else "Save to file tags")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAlbumMetadataScreen(
    album: AlbumItem,
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    val editor: MetadataEditService = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var albumName by remember(album.name, album.artist) {
        mutableStateOf(album.name.orEmpty())
    }
    var albumArtist by remember(album.name, album.artist) {
        mutableStateOf(album.artist.orEmpty())
    }
    var yearText by remember(album.name, album.artist) {
        mutableStateOf(
            album.songs.mapNotNull { it.year }.maxOrNull()?.toString().orEmpty()
        )
    }
    var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var coverMime by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canEdit = remember(album) { editor.isLocalAlbum(album) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val pair = editor.readImageBytes(uri)
            coverBytes = pair?.first
            coverMime = pair?.second
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit album") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = canEdit && !saving,
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                val year = yearText.trim().toIntOrNull()
                                val result = editor.saveAlbum(
                                    album,
                                    MetadataEditService.AlbumEdit(
                                        albumName = albumName.ifBlank { null },
                                        albumArtist = albumArtist.ifBlank { null },
                                        year = year,
                                        coverBytes = coverBytes,
                                        coverMime = coverMime
                                    )
                                )
                                saving = false
                                if (result.ok > 0) {
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    onSaved()
                                    onBack()
                                } else {
                                    error = result.message
                                }
                            }
                        }
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!canEdit) {
                Text(
                    "No writable local files in this album. Metadata edit is only available for local music.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(
                    song = album.songs.firstOrNull(),
                    size = 96.dp,
                    corner = 8.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        "${album.songs.size} tracks will be updated",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickImage.launch("image/*") },
                        enabled = canEdit && !saving
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (coverBytes != null) "Cover selected" else "Change cover")
                    }
                }
            }

            OutlinedTextField(
                value = albumName,
                onValueChange = { albumName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Album name") },
                singleLine = true,
                enabled = canEdit && !saving
            )
            OutlinedTextField(
                value = albumArtist,
                onValueChange = { albumArtist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Album artist") },
                singleLine = true,
                enabled = canEdit && !saving
            )
            OutlinedTextField(
                value = yearText,
                onValueChange = { yearText = it.filter { ch -> ch.isDigit() }.take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Year") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = canEdit && !saving
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val year = yearText.trim().toIntOrNull()
                        val result = editor.saveAlbum(
                            album,
                            MetadataEditService.AlbumEdit(
                                albumName = albumName.ifBlank { null },
                                albumArtist = albumArtist.ifBlank { null },
                                year = year,
                                coverBytes = coverBytes,
                                coverMime = coverMime
                            )
                        )
                        saving = false
                        if (result.ok > 0) {
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            onSaved()
                            onBack()
                        } else {
                            error = result.message
                        }
                    }
                },
                enabled = canEdit && !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving…" else "Save to file tags")
            }

            Text(
                "Changes are written into each local audio file. Folder cover.jpg is updated when you pick a new image.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
