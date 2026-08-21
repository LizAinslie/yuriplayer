package capital.yuri.yuriplayer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How many skeleton slots to paint when we don't know the real count. */
object LoadingEstimates {
    const val ALBUM_ROW = 8
    const val ARTIST_ROW = 6
    const val SONG_LIST = 10
    const val PLAYLIST_ROW = 5

    fun albums(known: Int?): Int =
        (if (known != null && known > 0) known else ALBUM_ROW).coerceIn(4, 16)

    fun artists(known: Int?): Int =
        (if (known != null && known > 0) known else ARTIST_ROW).coerceIn(4, 12)

    fun songs(known: Int?): Int =
        (if (known != null && known > 0) known else SONG_LIST).coerceIn(6, 14)

    fun playlists(known: Int?): Int =
        (if (known != null && known > 0) known else PLAYLIST_ROW).coerceIn(3, 10)
}

@Composable
fun PulseBox(modifier: Modifier, circle: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "skeleton")
    val alpha by inf.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val shape = if (circle) CircleShape else RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

@Composable
fun AlbumCardSkeleton(size: Dp = 148.dp) {
    Column(modifier = Modifier.width(size)) {
        PulseBox(Modifier.size(size).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(8.dp))
        PulseBox(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(6.dp))
        PulseBox(Modifier.fillMaxWidth(0.45f).height(10.dp))
    }
}

@Composable
fun ArtistCardSkeleton(size: Dp = 88.dp) {
    Column(
        modifier = Modifier.width(size),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PulseBox(Modifier.size(size), circle = true)
        Spacer(Modifier.height(8.dp))
        PulseBox(Modifier.fillMaxWidth().height(12.dp))
    }
}

@Composable
fun AlbumRowSkeleton(count: Int, cardSize: Dp = 148.dp) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        items(count) { AlbumCardSkeleton(cardSize) }
    }
}

@Composable
fun ArtistRowSkeleton(count: Int) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        items(count) { ArtistCardSkeleton() }
    }
}

@Composable
fun SongRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulseBox(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PulseBox(Modifier.fillMaxWidth(0.72f).height(14.dp))
            Spacer(Modifier.height(8.dp))
            PulseBox(Modifier.fillMaxWidth(0.4f).height(10.dp))
        }
    }
}

@Composable
fun SongListSkeleton(count: Int) {
    Column {
        repeat(count) { SongRowSkeleton() }
    }
}
