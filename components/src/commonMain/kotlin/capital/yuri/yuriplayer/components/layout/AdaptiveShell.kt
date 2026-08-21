package capital.yuri.yuriplayer.components.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phone: [content] fills, [bottomBar] as mini-player.
 * Tablet: content + optional sidebar.
 * Desktop: content | now-playing sidebar, full-width [bottomBar] underneath.
 */
@Composable
fun AdaptiveShell(
    widthClass: WindowWidthClass,
    bottomBar: @Composable () -> Unit,
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            if (widthClass == WindowWidthClass.Expanded) {
                VerticalDivider()
                Box(Modifier.width(340.dp).fillMaxHeight()) { sidebar() }
            }
        }
        HorizontalDivider()
        bottomBar()
    }
}
