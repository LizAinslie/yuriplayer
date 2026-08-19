package capital.yuri.yuriplayer.activities.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.TrackIdentity
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Resolves offerings for [song] and always exposes a Sources action
 * (icon still only when multi-source). Used so album / artist / playlist
 * rows do not depend on a parent passing offerings.
 */
@Composable
fun rememberSongOfferings(song: Song, seed: List<SourceOffering>? = null): List<SourceOffering> {
    val catalog: CatalogRepository = koinInject()
    var offerings by remember(song.songKey) { mutableStateOf(seed.orEmpty()) }
    LaunchedEffect(song.songKey, seed) {
        if (seed != null) {
            offerings = seed
            return@LaunchedEffect
        }
        offerings = withContext(Dispatchers.IO) {
            catalog.offeringsMatchingSong(song)
        }
    }
    return offerings
}

@Composable
fun rememberPreferSourceHandler(song: Song): (SourceOffering) -> Unit {
    val resolver: SourceResolver = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(song.songKey) {
        { off: SourceOffering ->
            scope.launch {
                resolver.setOverride(
                    scope = "TRACK",
                    scopeKey = TrackIdentity.of(song),
                    sourceId = off.sourceId,
                    sourceType = off.sourceType.name
                )
                Toast.makeText(
                    context,
                    "Prefer ${off.sourceName} for this track",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
