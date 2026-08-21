package capital.yuri.yuriplayer.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt

enum class DesktopNav { Home, Search }

enum class LibraryFilter { Recents, Playlists, Albums, Artists }

data class LibraryRailItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val circular: Boolean = false,
    val pinned: Boolean = false
)

@Composable
fun DesktopBentoLayout(
    nav: DesktopNav,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    canBack: Boolean,
    canForward: Boolean,
    onSettings: () -> Unit,
    libraryFilter: LibraryFilter,
    onLibraryFilter: (LibraryFilter) -> Unit,
    libraryItems: List<LibraryRailItem>,
    selectedLibraryId: String?,
    onLibraryItem: (LibraryRailItem) -> Unit,
    bottomBar: @Composable () -> Unit,
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showSidebar: Boolean = true,
    leftFraction: Float = 0.22f,
    rightFraction: Float = 0.26f,
    onLeftFraction: (Float, persist: Boolean) -> Unit = { _, _ -> },
    onRightFraction: (Float, persist: Boolean) -> Unit = { _, _ -> },
    content: @Composable () -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxSize().background(bg)) {
        TopChrome(
            nav = nav,
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            onHome = onHome,
            onBack = onBack,
            onForward = onForward,
            canBack = canBack,
            canForward = canForward,
            onSettings = onSettings
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val gap = 8.dp
            val handle = 6.dp
            var leftF = leftFraction.coerceIn(0.12f, 0.4f)
            var rightF = if (showSidebar) rightFraction.coerceIn(0.14f, 0.45f) else 0f
            if (showSidebar && leftF + rightF > 0.72f) {
                val scale = 0.72f / (leftF + rightF)
                leftF *= scale
                rightF *= scale
            }
            val leftW = maxWidth * leftF
            val rightW = maxWidth * rightF
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                LibraryRail(
                    filter = libraryFilter,
                    onFilter = onLibraryFilter,
                    items = libraryItems,
                    selectedId = selectedLibraryId,
                    onItem = onLibraryItem,
                    modifier = Modifier.width(leftW.coerceAtLeast(180.dp)).fillMaxHeight()
                )
                ResizeHandle(
                    onDragPx = { dx ->
                        onLeftFraction((leftFraction + dx / totalPx).coerceIn(0.14f, 0.38f), false)
                    },
                    onDragEnd = { onLeftFraction(leftFraction, true) }
                )
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
                if (showSidebar) {
                    ResizeHandle(
                        onDragPx = { dx ->
                            onRightFraction((rightFraction - dx / totalPx).coerceIn(0.16f, 0.42f), false)
                        },
                        onDragEnd = { onRightFraction(rightFraction, true) }
                    )
                    Surface(
                        modifier = Modifier.width(rightW.coerceAtLeast(200.dp)).fillMaxHeight(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        sidebar()
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { bottomBar() }
    }
}

@Composable
private fun ResizeHandle(
    onDragPx: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onHorizontalDrag = { _, dragAmount -> onDragPx(dragAmount) }
                )
            }
    )
}

@Composable
private fun TopChrome(
    nav: DesktopNav,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    canBack: Boolean,
    canForward: Boolean,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onForward, enabled = canForward) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
        }
        FilledIconButton(
            onClick = onHome,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Default.Home, contentDescription = "Home")
        }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQuery,
            modifier = Modifier.widthIn(min = 280.dp, max = 520.dp).weight(1.2f).height(48.dp),
            placeholder = { Text("What do you want to play?") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        Spacer(Modifier.weight(1f))
        FilledIconButton(
            onClick = onSettings,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun LibraryRail(
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    items: List<LibraryRailItem>,
    selectedId: String?,
    onItem: (LibraryRailItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Your library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                Modifier.padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = filter == LibraryFilter.Recents,
                    onClick = { onFilter(LibraryFilter.Recents) },
                    label = { Text("Recents") }
                )
                FilterChip(
                    selected = filter == LibraryFilter.Albums,
                    onClick = { onFilter(LibraryFilter.Albums) },
                    label = { Text("Albums") }
                )
                FilterChip(
                    selected = filter == LibraryFilter.Artists,
                    onClick = { onFilter(LibraryFilter.Artists) },
                    label = { Text("Artists") }
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    val selected = item.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onItem(item) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverArt(
                            model = item.artworkUri,
                            size = 48.dp,
                            corner = if (item.circular) 24.dp else 8.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                item.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (item.pinned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
