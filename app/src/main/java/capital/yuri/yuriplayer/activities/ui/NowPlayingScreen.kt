package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import capital.yuri.yuriplayer.player.RepeatMode
import org.koin.compose.koinInject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Composable
fun NowPlayingScreen(
    song: Song?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    snapshot: QueueSnapshot,
    peekNextSong: Song?,
    peekPrevSong: Song?,
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
    onRemoveCold: (Int) -> Unit,
    onPlayHistorySong: (Song) -> Unit = {},
    onClearHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeStore: PlayerThemeStore = koinInject()
    val baseScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val theme by themeStore.current.collectAsState()
    val nextTheme by themeStore.peekNext.collectAsState()
    val prevTheme by themeStore.peekPrev.collectAsState()

    var showQueue by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }
    var hFrac by remember { mutableFloatStateOf(0f) }
    var dismissFrac by remember { mutableFloatStateOf(0f) }
    var topPull by remember { mutableFloatStateOf(0f) }

    val dismissThreshold = with(density) { 140.dp.toPx() }

    LaunchedEffect(song?.id, song?.path) {
        themeStore.updateCurrent(context, song, baseScheme)
    }
    LaunchedEffect(peekNextSong?.id, peekPrevSong?.id, song?.id) {
        themeStore.updateNeighbors(context, peekNextSong, peekPrevSong, baseScheme)
    }

    LaunchedEffect(positionMs, durationMs, sliding) {
        if (!sliding && durationMs > 0) {
            sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    val playerColors = theme?.colors ?: fallbackPlayerColors(baseScheme)
    val blendTarget = when {
        hFrac < -0.02f -> nextTheme?.colors
        hFrac > 0.02f -> prevTheme?.colors
        else -> null
    }
    val blendT = abs(hFrac).coerceIn(0f, 1f)
    val shownColors = if (blendTarget != null && blendT > 0f) {
        lerpPlayerColors(playerColors, blendTarget, blendT)
    } else playerColors

    val scheme = playerColorScheme(shownColors, baseScheme)
    ThemedStatusBar(color = scheme.background, enabled = true)

    MaterialTheme(colorScheme = scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background.copy(alpha = 1f - maxOf(dismissFrac, topPull / dismissThreshold) * 0.2f)
        ) {
            if (showQueue) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { showQueue = false },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(Icons.Default.ExpandMore, "Back", tint = scheme.onBackground)
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
                        onPlayHistorySong = onPlayHistorySong,
                        onClearHistory = onClearHistory,
                        modifier = Modifier.weight(1f)
                    )
                }
                return@Surface
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (topPull > dismissThreshold) onCollapse()
                                    topPull = 0f
                                    dismissFrac = 0f
                                },
                                onDragCancel = {
                                    topPull = 0f
                                    dismissFrac = 0f
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    if (amount > 0 || topPull > 0f) {
                                        topPull = (topPull + amount).coerceAtLeast(0f)
                                        dismissFrac = (topPull / dismissThreshold).coerceIn(0f, 1.5f)
                                    }
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(Icons.Default.ExpandMore, "Close", tint = scheme.onBackground)
                        }
                    }

                    SwipeableAlbumArt(
                        current = theme,
                        next = nextTheme,
                        prev = prevTheme,
                        onSwipeNext = onNext,
                        onSwipePrev = onPrev,
                        onDismiss = onCollapse,
                        onHorizontalFraction = { hFrac = it },
                        onDismissFraction = { dismissFrac = it },
                        horizontalInset = 20.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                ) {
                    MarqueeText(
                        text = song?.displayTitle ?: "Not playing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground
                    )
                    MarqueeText(
                        text = song?.displayArtist ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = shownColors.muted
                    )
                    MarqueeText(
                        text = song?.displayAlbum ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = shownColors.muted.copy(alpha = 0.75f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                        inactiveColor = shownColors.muted.copy(alpha = 0.35f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = shownColors.muted)
                        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = shownColors.muted)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleShuffle) {
                            Icon(
                                Icons.Default.Shuffle,
                                "Shuffle",
                                tint = if (snapshot.shuffleEnabled) scheme.primary
                                else scheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = onPrev) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                "Previous",
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
                                if (playing) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                                tint = scheme.onPrimary
                            )
                        }
                        IconButton(onClick = onNext) {
                            Icon(
                                Icons.Default.SkipNext,
                                "Next",
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
                                repeatLabel(snapshot.repeatMode),
                                tint = if (active) scheme.primary else scheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append(repeatLabel(snapshot.repeatMode))
                                if (snapshot.shuffleEnabled) append(" · Shuffle")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = shownColors.muted
                        )
                        IconButton(onClick = { showQueue = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                "Queue",
                                tint = scheme.onBackground.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lerpPlayerColors(a: PlayerColors, b: PlayerColors, t: Float): PlayerColors {
    val x = t.coerceIn(0f, 1f)
    return PlayerColors(
        container = lerp(a.container, b.container, x),
        onContainer = lerp(a.onContainer, b.onContainer, x),
        accent = lerp(a.accent, b.accent, x),
        onAccent = lerp(a.onAccent, b.onAccent, x),
        muted = lerp(a.muted, b.muted, x),
        surface = lerp(a.surface, b.surface, x),
        onSurface = lerp(a.onSurface, b.onSurface, x)
    )
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
