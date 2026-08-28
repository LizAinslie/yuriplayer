package capital.yuri.yuriplayer.activities.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Nested tab screens (catalog pages, browse-all) register [androidx.activity.compose.BackHandler]
 * callbacks. Those must disable while a per-tab album/artist/playlist overlay is showing so
 * predictive back pops the overlay instead of the tab's inner page.
 */
val LocalTabBackEnabled = staticCompositionLocalOf { true }
