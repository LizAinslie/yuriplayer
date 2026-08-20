package capital.yuri.yuriplayer.activities.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import capital.yuri.yuriplayer.data.MetadataEnrichmentService
import capital.yuri.yuriplayer.data.PlayerThemeStore
import capital.yuri.yuriplayer.data.Song
import org.koin.compose.koinInject

/**
 * When MusicBrainz/Cover Art Archive writes a new cover, [MetadataEnrichmentService.coverGeneration]
 * ticks. Force-refresh the now-playing palette so Material You colors match the new art.
 */
@Composable
fun CoverThemeRefresh(
    song: Song?,
    baseScheme: ColorScheme,
    peekNext: Song? = null,
    peekPrev: Song? = null
) {
    val context = LocalContext.current
    val enrichment: MetadataEnrichmentService = koinInject()
    val themeStore: PlayerThemeStore = koinInject()
    val coverGen by enrichment.coverGeneration.collectAsState()
    // Seed to the current gen so entering Now Playing does not force-decode art
    // (that hitch was stalling audio). Only run when a NEW cover actually lands.
    val lastApplied = remember { mutableLongStateOf(coverGen) }

    LaunchedEffect(coverGen, song?.path, song?.id) {
        if (coverGen <= 0L || coverGen == lastApplied.longValue) return@LaunchedEffect
        lastApplied.longValue = coverGen
        themeStore.updateCurrent(
            context = context,
            song = song,
            baseScheme = baseScheme,
            forceRefresh = true
        )
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
    }
}
