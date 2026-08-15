package capital.yuri.yuriplayer.activities.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    LaunchedEffect(coverGen, song?.path, song?.id) {
        if (coverGen <= 0L) return@LaunchedEffect
        themeStore.updateCurrent(
            context = context,
            song = song,
            baseScheme = baseScheme,
            forceRefresh = true
        )
        themeStore.updateNeighbors(context, peekNext, peekPrev, baseScheme)
    }
}
