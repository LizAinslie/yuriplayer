package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistSheet(
    onDismiss: () -> Unit,
    onCreated: (Playlist) -> Unit = {}
) {
    val repo: PlaylistRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "New playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { pickImage.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                val uri = imageUri
                if (uri != null) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { imageUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                RoundedCornerShape(14.dp)
                            )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Add cover",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("My playlist") },
                leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (saving) return@Button
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        Toast.makeText(context, "Enter a name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        try {
                            val pl = repo.create(trimmed)
                            imageUri?.let { uri ->
                                repo.setCustomImage(pl.id, uri.toString())
                            }
                            val resolved = pl.copy(customImageUri = imageUri?.toString())
                            Toast.makeText(context, "Created ${resolved.name}", Toast.LENGTH_SHORT).show()
                            onCreated(resolved)
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not create playlist", Toast.LENGTH_SHORT).show()
                            saving = false
                        }
                    }
                },
                enabled = !saving && name.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Creating…" else "Create")
            }
        }
    }
}
