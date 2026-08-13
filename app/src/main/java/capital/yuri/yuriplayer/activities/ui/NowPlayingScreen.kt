package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.AlbumArtResolver
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import capital.yuri.yuriplayer.player.RepeatMode
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun NowPlayingScreen(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    snapshot: QueueSnapshot,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (Int, Int) -> Unit,
    onMoveCold: (Int, Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit
) {
    val context = LocalContext.current
    val baseScheme = MaterialTheme.colorScheme
    var playerColors by remember { mutableStateOf(fallbackPlayerColors(baseScheme)) }
    var showQueue by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }

    LaunchedEffect(song?.path, song?.contentUri) {
        if (song != null) {
            val bmp: Bitmap? = AlbumArtResolver.load(context, song, maxSize = 768)
            playerColors = extractPlayerColors(bmp, baseScheme)
        } else {
            playerColors = fallbackPlayerColors(baseScheme)
        }
    }

    LaunchedEffect(positionMs, durationMs, sliding) {
        if (!sliding && durationMs > 0) {
            sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    val scheme = playerColorScheme(playerColors, baseScheme)

    MaterialTheme(colorScheme = scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background
        ) {
            if (showQueue) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { showQueue = false },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = "Back",
                                tint = scheme.onBackground
                            )
                        }
                        Text(
                            "Queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onBackground
                        )
                    }
                    QueuePanel(
                        snapshot = snapshot,
                        onPlayItem = onPlayItem,
                        onMoveHot = onMoveHot,
                        onMoveCold = onMoveCold,
                        onRemoveHot = onRemoveHot,
                        onRemoveCold = onRemoveCold,
                        modifier = Modifier.weight(1f)
                    )
                }
                return@Surface
            }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Centered collapse chevron (Spotify-style)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "Close",
                            tint = scheme.onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .aspectRatio(1f)
                ) {
                    AlbumArt(
                        song = song,
                        modifier = Modifier.fillMaxSize(),
                        corner = 16.dp
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    song?.displayTitle ?: "Not playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    song?.displayArtist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = playerColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    song?.displayAlbum ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = playerColors.muted.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                WavySeekBar(
                    progress = sliderPosition,
                    playing = playing,
                    onProgressChange = {
                        sliding = true
                        sliderPosition = it
                    },
                    onProgressChangeFinished = {
                        sliding = false
                        if (durationMs > 0) onSeek((sliderPosition * durationMs).toLong())
                    },
                    activeColor = scheme.primary,
                    inactiveColor = playerColors.muted.copy(alpha = 0.35f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = playerColors.muted)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = playerColors.muted)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (snapshot.shuffleEnabled) scheme.primary
                            else scheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onPrev) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(40.dp),
                            tint = scheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier
                            .size(72.dp)
                            .background(scheme.primary, shape = MaterialTheme.shapes.extraLarge)
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                            tint = scheme.onPrimary
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(40.dp),
                            tint = scheme.onBackground
                        )
                    }
                    IconButton(onClick = onCycleRepeat) {
                        val (icon, active) = when (snapshot.repeatMode) {
                            RepeatMode.OFF -> Icons.Default.Repeat to false
                            RepeatMode.ONE -> Icons.Default.RepeatOne to true
                            RepeatMode.COLD -> Icons.Default.Repeat to true
                        }
                        Icon(
                            icon,
                            contentDescription = repeatLabel(snapshot.repeatMode),
                            tint = if (active) scheme.primary else scheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }

                Text(
                    buildString {
                        append(repeatLabel(snapshot.repeatMode))
                        if (snapshot.shuffleEnabled) append(" · Shuffle")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = playerColors.muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Queue control bottom-right (Spotify-style)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = scheme.onBackground.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

private fun repeatLabel(mode: RepeatMode): String = when (mode) {
    RepeatMode.OFF -> "Repeat off"
    RepeatMode.ONE -> "Repeat one"
    RepeatMode.COLD -> "Repeat all"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
