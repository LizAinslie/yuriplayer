package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.dialog.InWindowPanel
import capital.yuri.yuriplayer.components.model.AlbumPageModel
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.desktop.TagWriter
import java.io.File

@Composable
fun EditSongDialog(
    track: Song,
    onDismiss: () -> Unit,
    onSaved: (Song) -> Unit
) {
    val file = track.path?.let { File(it) }
    val writable = file != null && file.isFile && file.canWrite()
    var title by remember(track.songKey) { mutableStateOf(track.title.orEmpty()) }
    var artist by remember(track.songKey) { mutableStateOf(track.artist.orEmpty()) }
    var genre by remember(track.songKey) { mutableStateOf(track.genre.orEmpty()) }
    var coverFile by remember { mutableStateOf<File?>(null) }
    var cropSource by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    cropSource?.let { src ->
        ImageCropDialog(
            source = src,
            title = "Crop cover",
            onCancel = { cropSource = null },
            onCropped = {
                coverFile = it
                cropSource = null
            }
        )
    }

    InWindowPanel(onDismiss = onDismiss, modifier = Modifier.size(480.dp, 520.dp)) {
        Surface {
            Column(Modifier.padding(20.dp)) {
                Text("Edit song", style = MaterialTheme.typography.titleLarge)
                if (!writable) {
                    Text(
                        "This file isn’t writable (remote or read-only).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                CoverArt(
                    model = coverFile?.toURI()?.toString() ?: track.albumArtUri,
                    size = 96.dp,
                    corner = 8.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                TextButton(
                    onClick = { cropSource = DesktopFiles.pickImage() },
                    enabled = writable
                ) { Text("Change cover") }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = writable,
                        onClick = {
                            val f = file ?: return@TextButton
                            val result = TagWriter.write(
                                f,
                                TagWriter.SongEdit(
                                    title = title.ifBlank { null },
                                    artist = artist.ifBlank { null },
                                    genre = genre.ifBlank { null }
                                ),
                                coverJpeg = coverFile?.readBytes()
                            )
                            result.fold(
                                onSuccess = {
                                    onSaved(
                                        track.copy(
                                            title = title.ifBlank { track.title },
                                            artist = artist.ifBlank { track.artist },
                                            genre = genre.ifBlank { track.genre },
                                            albumArtUri = coverFile?.toURI()?.toString() ?: track.albumArtUri
                                        )
                                    )
                                    onDismiss()
                                },
                                onFailure = { error = it.message ?: "Couldn’t save tags" }
                            )
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun EditAlbumDialog(
    album: AlbumPageModel,
    tracks: List<Song>,
    onDismiss: () -> Unit,
    onSaved: (List<Song>) -> Unit
) {
    val writable = tracks.mapNotNull { it.path?.let { p -> File(p) } }.any { it.isFile && it.canWrite() }
    var title by remember(album.id) { mutableStateOf(album.title) }
    var artist by remember(album.id) { mutableStateOf(album.artist) }
    var year by remember(album.id) { mutableStateOf(album.year?.toString().orEmpty()) }
    var genre by remember(album.id) { mutableStateOf(tracks.firstOrNull()?.genre.orEmpty()) }
    var coverFile by remember { mutableStateOf<File?>(null) }
    var cropSource by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    cropSource?.let { src ->
        ImageCropDialog(
            source = src,
            onCancel = { cropSource = null },
            onCropped = {
                coverFile = it
                cropSource = null
            }
        )
    }

    InWindowPanel(onDismiss = onDismiss, modifier = Modifier.size(480.dp, 560.dp)) {
        Surface {
            Column(Modifier.padding(20.dp)) {
                Text("Edit album", style = MaterialTheme.typography.titleLarge)
                CoverArt(
                    model = coverFile?.toURI()?.toString() ?: album.artworkUri,
                    size = 96.dp,
                    corner = 8.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                TextButton(
                    onClick = { cropSource = DesktopFiles.pickImage() },
                    enabled = writable
                ) { Text("Change cover") }
                OutlinedTextField(title, { title = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(artist, { artist = it }, label = { Text("Album artist") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = writable,
                        onClick = {
                            val coverBytes = coverFile?.readBytes()
                            val yearInt = year.toIntOrNull()
                            val updated = ArrayList<Song>()
                            var failed = 0
                            tracks.forEach { t ->
                                val f = t.path?.let { File(it) }
                                if (f == null || !f.canWrite()) {
                                    failed++
                                    updated += t
                                    return@forEach
                                }
                                val result = TagWriter.write(
                                    f,
                                    TagWriter.SongEdit(
                                        album = title.ifBlank { null },
                                        albumArtist = artist.ifBlank { null },
                                        year = yearInt,
                                        genre = genre.ifBlank { null }
                                    ),
                                    coverJpeg = coverBytes
                                )
                                if (result.isFailure) failed++
                                updated += t.copy(
                                    album = title.ifBlank { t.album },
                                    albumArtist = artist.ifBlank { t.albumArtist },
                                    year = yearInt ?: t.year,
                                    genre = genre.ifBlank { t.genre },
                                    albumArtUri = coverFile?.toURI()?.toString() ?: t.albumArtUri
                                )
                            }
                            if (failed == tracks.size) {
                                error = "Couldn’t write tags on these files"
                            } else {
                                onSaved(updated)
                                onDismiss()
                            }
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}
