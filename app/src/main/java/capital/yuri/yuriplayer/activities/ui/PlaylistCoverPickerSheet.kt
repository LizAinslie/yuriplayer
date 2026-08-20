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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlaylistCoverSlot
import capital.yuri.yuriplayer.data.PlaylistRepository
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Art size inside each carousel page — leaves room for caption + actions. */
private val CoverArtSize = 200.dp

/**
 * Swipeable cover carousel (same pattern as [AlbumCoverPickerSheet]).
 * Long-press playlist art → this sheet. Tap to activate · lock for secret · + for custom.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    val pageCount = covers.size + 1
    val initial = run {
        val idx = covers.indexOfFirst { it.isActive }
        if (idx >= 0) idx else 0
    }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { pageCount })

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Playlist cover",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                playlistName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Text(
                "Swipe to preview · tap to use",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 56.dp),
                pageSpacing = 20.dp,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                if (page < covers.size) {
                    val slot = covers[page]
                    CoverCarouselPage(
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
                } else {
                    AddCarouselPage(
                        secret = addAsSecret,
                        onClick = { pick.launch("image/*") }
                    )
                }
            }

            if (pageCount > 1) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    repeat(pageCount) { i ->
                        val selected = pagerState.currentPage == i
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(i) }
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                TextButton(onClick = { addAsSecret = !addAsSecret }) {
                    Icon(
                        if (addAsSecret) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (addAsSecret) "Next add: secret" else "Next add: public")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CoverCarouselPage(
    slot: PlaylistCoverSlot,
    onSelect: () -> Unit,
    onToggleSecret: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(CoverArtSize)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = if (slot.isActive) 3.dp else 0.dp,
                    color = if (slot.isActive) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(slot.uri)
                    .memoryCacheKey("pl-slot:${slot.id}")
                    .diskCacheKey("pl-slot:${slot.id}")
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (slot.isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (slot.isSecret) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Secret",
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            when {
                slot.isActive && slot.isSecret -> "Active · secret"
                slot.isActive -> "Active"
                slot.isSecret -> "Secret"
                else -> "Tap to use"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (slot.isActive) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleSecret, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (slot.isSecret) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = if (slot.isSecret) "Make public" else "Make secret",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddCarouselPage(secret: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(CoverArtSize)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add cover",
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Icon(
                    if (secret) Icons.Default.Lock else Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (secret) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            if (secret) "Add secret cover" else "Custom image",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "File → crop",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        // Match action-row height on cover pages so pages align
        Spacer(modifier = Modifier.height(44.dp))
    }
}
