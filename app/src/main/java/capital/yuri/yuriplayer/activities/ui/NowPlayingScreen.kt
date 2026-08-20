package capital.yuri.yuriplayer.activities.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.ColdSourceType
import capital.yuri.yuriplayer.player.PlayerController
import capital.yuri.yuriplayer.player.QueueLane
import capital.yuri.yuriplayer.player.QueueSnapshot
import capital.yuri.yuriplayer.player.RepeatMode
import capital.yuri.yuriplayer.player.radio.RadioAlgorithmId
import capital.yuri.yuriplayer.player.radio.RadioSession
import capital.yuri.yuriplayer.player.radio.RadioSessionKind
import capital.yuri.yuriplayer.player.radio.RadioSourcePrefs
import org.koin.compose.koinInject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val PREV_RESTART_MS = 3_000L

/** Radio + small settings cog badge (no single Material glyph for both). */
@Composable
private fun RadioSettingsIcon(
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Radio,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .background(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.extraSmall
                )
                .padding(1.dp)
        )
    }
}

/** Prefer live session; if only coldSource is RADIO (e.g. after restore), synthesize one. */
private fun radioSessionForSettings(snapshot: QueueSnapshot): RadioSession? {
    snapshot.radioSession?.takeIf { it.active || snapshot.coldSource?.type == ColdSourceType.RADIO }
        ?.let { return it }
    if (snapshot.coldSource?.type != ColdSourceType.RADIO) return null
    val src = snapshot.coldSource
    return RadioSession(
        kind = RadioSessionKind.CUSTOM,
        displayName = src.title?.takeIf { it.isNotBlank() } ?: "Radio",
        algorithmId = RadioAlgorithmId.PLAYBACK,
        seedId = src.id,
        seedTitle = src.title,
        active = true,
        prefs = RadioSourcePrefs()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onForcePrev: () -> Unit = onPrev,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFraction: ((Float) -> Unit)? = null,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayItem: (QueueLane, Int) -> Unit,
    onMoveHot: (Int, Int) -> Unit,
    onMoveCold: (Int, Int) -> Unit,
    onRemoveHot: (Int) -> Unit,
    onRemoveCold: (Int) -> Unit,
    onMoveColdToHot: (Int) -> Unit = {},
    onClearHotQueue: () -> Unit = {},
    onPlayHistorySong: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onGoToAlbum: (Song) -> Unit = {},
    onGoToArtist: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {}
) {
    val themeStore: PlayerThemeStore = koinInject()
    val player: PlayerController = koinInject()
    val baseScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val theme by themeStore.current.collectAsState()
    val nextTheme by themeStore.peekNext.collectAsState()
    val prevTheme by themeStore.peekPrev.collectAsState()

    CoverThemeRefresh(
        song = song,
        baseScheme = baseScheme,
        peekNext = peekNextSong,
        peekPrev = peekPrevSong
    )

    var showQueue by remember { mutableStateOf(false) }
    var showSongMenu by remember { mutableStateOf(false) }
    var showRadioSettings by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }
    var hFrac by remember { mutableFloatStateOf(0f) }
    var dismissFrac by remember { mutableFloatStateOf(0f) }
    var topPull by remember { mutableFloatStateOf(0f) }

    var skipToken by remember { mutableLongStateOf(0L) }
    var skipDirection by remember { mutableIntStateOf(0) }
    var skipCommit by remember { mutableStateOf(true) }
    var ignoreSongChangeAnim by remember { mutableStateOf(false) }

    val dismissThreshold = with(density) { 140.dp.toPx() }

    val canSwipePrev = peekPrevSong != null
    val buttonGoesToPrevTrack = positionMs <= PREV_RESTART_MS && canSwipePrev
    val isRadio = snapshot.isRadio
    val radioSettingsSession = remember(snapshot.radioSession, snapshot.coldSource) {
        radioSessionForSettings(snapshot)
    }

    val songKey = song?.path ?: song?.contentUri?.toString()
    var seenSongKey by remember { mutableStateOf(songKey) }

    fun requestSkipNext() {
        ignoreSongChangeAnim = true
        skipCommit = true
        skipDirection = -1
        skipToken++
    }

    fun requestSkipPrev() {
        if (!buttonGoesToPrevTrack) {
            ignoreSongChangeAnim = true
            onPrev()
            return
        }
        ignoreSongChangeAnim = true
        skipCommit = true
        skipDirection = 1
        skipToken++
    }

    LaunchedEffect(songKey) {
        val previous = seenSongKey
        seenSongKey = songKey
        if (previous != songKey) {
            sliding = false
            sliderPosition = 0f
        }
        if (previous == null || songKey == null || previous == songKey) return@LaunchedEffect
        if (ignoreSongChangeAnim) {
            ignoreSongChangeAnim = false
            return@LaunchedEffect
        }
        if (showQueue || skipDirection != 0) return@LaunchedEffect
        val id = song?.id
        val path = song?.path
        val matchesNext = nextTheme?.let { it.songId == id || (path != null && it.path == path) } == true
        val matchesPrev = prevTheme?.let { it.songId == id || (path != null && it.path == path) } == true
        skipCommit = false
        skipDirection = if (matchesPrev && !matchesNext) 1 else -1
        skipToken++
    }

    // Theme is kept warm by MainActivity on song change. Don't re-extract here —
    // opening Now Playing used to hitch audio via Palette on Main.

    val displayedProgress = if (sliding || durationMs <= 0L) {
        sliderPosition
    } else {
        (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    val playerColors = theme?.colors ?: fallbackPlayerColors(baseScheme)
    val blendTarget = when {
        hFrac < -0.02f -> nextTheme?.colors
        hFrac > 0.02f && canSwipePrev -> prevTheme?.colors
        else -> null
    }
    val blendT = abs(hFrac).coerceIn(0f, 1f)
    val shownColors = if (blendTarget != null && blendT > 0f) {
        lerpPlayerColors(playerColors, blendTarget, blendT)
    } else playerColors

    val scheme = playerColorScheme(shownColors, baseScheme, useArtBackground = true)
    ThemedStatusBar(color = scheme.background, enabled = true)

    val playingFromLabel = remember(snapshot.lane, snapshot.coldSource, snapshot.radioSession, song?.displayAlbum) {
        when (snapshot.lane) {
            QueueLane.HOT -> "Playing from queue"
            QueueLane.COLD -> {
                val name = when {
                    snapshot.isRadio ->
                        snapshot.radioSession?.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: snapshot.coldSource?.title?.takeIf { it.isNotBlank() }
                            ?: "Radio"
                    else ->
                        snapshot.coldSource?.title?.takeIf { it.isNotBlank() }
                            ?: song?.displayAlbum?.takeIf { it.isNotBlank() }
                            ?: when (snapshot.coldSource?.type) {
                                ColdSourceType.ALBUM -> "album"
                                ColdSourceType.PLAYLIST -> "playlist"
                                ColdSourceType.ARTIST -> "artist"
                                ColdSourceType.SONGS -> "songs"
                                ColdSourceType.RADIO -> "radio"
                                ColdSourceType.UNKNOWN, null -> null
                            }
                }
                name?.let { "Playing from $it" }
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background.copy(
                alpha = 1f - maxOf(dismissFrac, topPull / dismissThreshold) * 0.2f
            )
        ) {
            if (showQueue) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        nowPlaying = song,
                        onPlayItem = onPlayItem,
                        onMoveHot = onMoveHot,
                        onMoveCold = onMoveCold,
                        onRemoveHot = onRemoveHot,
                        onRemoveCold = onRemoveCold,
                        onMoveColdToHot = onMoveColdToHot,
                        onClearHotQueue = onClearHotQueue,
                        onPlayHistorySong = onPlayHistorySong,
                        onAddHistoryToQueue = onAddToQueue,
                        onClearHistory = onClearHistory,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    )

                    QueueTransportBar(
                        playing = playing,
                        onPrev = onPrev,
                        onToggle = onToggle,
                        onNext = onNext
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(Icons.Default.ExpandMore, "Close", tint = scheme.onBackground)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (isRadio) {
                            IconButton(onClick = { showRadioSettings = true }) {
                                RadioSettingsIcon(tint = scheme.primary)
                            }
                        }
                    }

                    if (playingFromLabel != null) {
                        Text(
                            text = playingFromLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 8.dp)
                        )
                    }

                    SwipeableAlbumArt(
                        current = theme,
                        next = nextTheme,
                        prev = if (canSwipePrev) prevTheme else null,
                        onSwipeNext = {
                            ignoreSongChangeAnim = true
                            onNext()
                        },
                        onSwipePrev = {
                            ignoreSongChangeAnim = true
                            onForcePrev()
                        },
                        onPromoteNext = { themeStore.promoteNext() },
                        onPromotePrev = { themeStore.promotePrev() },
                        onDismiss = onCollapse,
                        onHorizontalFraction = { hFrac = it },
                        onDismissFraction = { dismissFrac = it },
                        allowPrevTrackChange = canSwipePrev,
                        skipToken = skipToken,
                        skipDirection = skipDirection,
                        commitSkip = skipCommit,
                        onSkipConsumed = {
                            skipDirection = 0
                            skipCommit = true
                        },
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
                    AnimatedContent(
                        targetState = songKey ?: "none",
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "npMeta"
                    ) { _ ->
                        Column {
                            MarqueeText(
                                text = song?.displayTitle ?: "Not playing",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onBackground
                            )
                            MarqueeText(
                                text = song?.displayArtist ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = scheme.onBackground.copy(alpha = 0.65f)
                            )
                            MarqueeText(
                                text = song?.displayAlbum ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    WavySeekBar(
                        progress = displayedProgress,
                        playing = playing,
                        onProgressChange = {
                            sliding = true
                            sliderPosition = it
                        },
                        onProgressChangeFinished = { fraction ->
                            sliderPosition = fraction
                            if (onSeekFraction != null) {
                                onSeekFraction(fraction)
                            } else if (durationMs > 0L) {
                                val targetMs = (fraction.toDouble() * durationMs.toDouble())
                                    .toLong()
                                    .coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L))
                                onSeek(targetMs)
                            }
                            sliding = false
                        },
                        activeColor = scheme.primary,
                        inactiveColor = scheme.onBackground.copy(alpha = 0.28f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onBackground.copy(alpha = 0.55f)
                        )
                        Text(
                            formatTime(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onBackground.copy(alpha = 0.55f)
                        )
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
                        IconButton(onClick = { requestSkipPrev() }) {
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
                                contentDescription = if (playing) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                                tint = scheme.onPrimary
                            )
                        }
                        IconButton(onClick = { requestSkipNext() }) {
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (song != null) showSongMenu = true },
                            enabled = song != null
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = scheme.onBackground.copy(alpha = if (song != null) 0.85f else 0.35f)
                            )
                        }

                        Text(
                            buildString {
                                append(repeatLabel(snapshot.repeatMode))
                                if (snapshot.shuffleEnabled) append(" · Shuffle")
                                if (isRadio) append(" · Radio")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onBackground.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )

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

            if (showSongMenu && song != null) {
                ModalBottomSheet(
                    onDismissRequest = { showSongMenu = false },
                    sheetState = rememberModalBottomSheetState()
                ) {
                    MediaSheetHeader(
                        song = song,
                        title = song.displayTitle,
                        subtitle = "${song.displayArtist} · ${song.displayAlbum}"
                    )
                    MediaSheetItem(
                        label = "Go to album",
                        onClick = {
                            showSongMenu = false
                            onGoToAlbum(song)
                        }
                    )
                    MediaSheetItem(
                        label = "Go to artist",
                        onClick = {
                            showSongMenu = false
                            onGoToArtist(song)
                        }
                    )
                    MediaSheetItem(
                        label = "Add to playlist",
                        onClick = {
                            showSongMenu = false
                            onAddToPlaylist(song)
                        }
                    )
                    MediaSheetItem(
                        label = "Add to queue",
                        onClick = {
                            showSongMenu = false
                            onAddToQueue(song)
                        }
                    )
                    MediaSheetItem(
                        label = "Sources",
                        onClick = {
                            showSongMenu = false
                        }
                    )
                    MediaSheetBottomPad()
                }
            }

            // Always open when isRadio — never silently clear the flag mid-composition
            if (showRadioSettings && isRadio) {
                val session = radioSettingsSession
                if (session != null) {
                    RadioSettingsSheet(
                        session = session,
                        onApply = { prefs -> player.applyRadioPrefs(prefs) },
                        onDismiss = { showRadioSettings = false }
                    )
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
