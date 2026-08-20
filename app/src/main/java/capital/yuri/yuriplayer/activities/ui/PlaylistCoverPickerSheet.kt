package capital.yuri.yuriplayer.activities.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import capital.yuri.yuriplayer.data.PlaylistCoverSlot
import capital.yuri.yuriplayer.data.PlaylistRepository
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Manage multiple playlist covers — including secret alternates.
 * Tap a cover to make it active; toggle lock for secret; + to add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCoverPickerSheet(
    playlistId: String,
    playlistName: String,
    onDismiss: () -> Unit,
    onRequestCrop: (Uri, addAsSecret: Boolean) -> Unit
) {
    val repo: PlaylistRepository = koinInject()
    val covers by repo.observeCovers(playlistId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addAsSecret by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onRequestCrop(uri, addAsSecret)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = playlistName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Covers — tap to use · lock for secret alternates",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(covers, key = { it.id }) { slot ->
                    CoverThumb(
                        slot = slot,
                        onSelect = {
                            scope.launch {
                                repo.setActiveCover(playlistId, slot.id)
                                Toast.makeText(context, "Cover set", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleSecret = {
                            scope.launch {
                                repo.setCoverSecret(slot.id, !slot.isSecret)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                repo.removeCover(playlistId, slot.id)
                                Toast.makeText(context, "Cover removed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                item {
                    AddThumb(
                        secret = addAsSecret,
                        onClick = { pick.launch("image/*") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = { addAsSecret = !addAsSecret }
                ) {
                    Icon(
                        if (addAsSecret) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (addAsSecret) "Next add: secret" else "Next add: public")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CoverThumb(
    slot: PlaylistCoverSlot,
    onSelect: () -> Unit,
    onToggleSecret: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val border = when {
        slot.isActive -> MaterialTheme.colorScheme.primary
        slot.isSecret -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, border, RoundedCornerShape(10.dp))
                .clickable(onClick = onSelect)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(slot.uri)
                    .memoryCacheKey("pl-slot:${slot.id}")
                    .diskCacheKey("pl-slot:${slot.id}")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            if (slot.isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (slot.isSecret) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Secret",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(18.dp)
                )
            }
        }
        Row {
            IconButton(onClick = onToggleSecret, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (slot.isSecret) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = if (slot.isSecret) "Make public" else "Make secret",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddThumb(secret: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, contentDescription = "Add cover")
                if (secret) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
        Text(
            if (secret) "Secret" else "Add",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
