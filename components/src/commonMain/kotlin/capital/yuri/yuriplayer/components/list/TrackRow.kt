package capital.yuri.yuriplayer.components.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.art.CoverArt
import capital.yuri.yuriplayer.components.model.TrackRowModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: TrackRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    showAlbum: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    contextItems: List<ContextAction> = emptyList()
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ContextMenuAnchor(items = contextItems) { openMenu ->
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(if (track.highlighted) highlight else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        when {
                            contextItems.isNotEmpty() -> openMenu()
                            onLongClick != null -> onLongClick()
                        }
                    }
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (track.trackNumber != null && !showCover) {
            Text(
                track.trackNumber.toString(),
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else if (showCover) {
            CoverArt(model = track.artworkUri, size = 44.dp, corner = 6.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (track.highlighted) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            val sub = if (showAlbum) "${track.artist} · ${track.album}" else track.artist
            Text(
                sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        if (onToggleLike != null) {
            LikeHeart(liked = liked, onToggle = onToggleLike)
        }
        track.durationMs?.let {
            Text(
                formatTime(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
