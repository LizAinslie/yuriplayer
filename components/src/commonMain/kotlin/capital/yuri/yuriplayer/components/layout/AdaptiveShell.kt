package capital.yuri.yuriplayer.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AdaptiveShell(
    widthClass: WindowWidthClass,
    bottomBar: @Composable () -> Unit,
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Column(
        modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
            ) { content() }
            if (widthClass == WindowWidthClass.Expanded) {
                VerticalDivider(color = outline)
                Box(Modifier.width(340.dp).fillMaxHeight()) { sidebar() }
            }
        }
        HorizontalDivider(color = outline)
        bottomBar()
    }
}
