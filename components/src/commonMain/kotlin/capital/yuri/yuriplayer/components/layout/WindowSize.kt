package capital.yuri.yuriplayer.components.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthClass {
    /** Phone, or a narrow window. Collapsing headers, mini-player. */
    Compact,
    /** Tablet / split. Side-by-side album pages, optional sidebar. */
    Medium,
    /** Desktop. Right now-playing sidebar + bottom transport. */
    Expanded
}

fun windowWidthClass(width: Dp): WindowWidthClass = when {
    width < 600.dp -> WindowWidthClass.Compact
    width < 1100.dp -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

@Composable
fun rememberWindowWidthClass(width: Dp): WindowWidthClass =
    remember(width) { windowWidthClass(width) }
