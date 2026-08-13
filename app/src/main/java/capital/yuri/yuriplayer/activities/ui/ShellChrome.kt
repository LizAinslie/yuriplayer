package capital.yuri.yuriplayer.activities.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color

/**
 * Chrome colors for the active navigation node.
 *
 * - [statusBar] / [topBar]: match each other (navbar + system bar)
 * - [contentBackground]: page fill
 * - [miniPlayerBackground]: now-playing strip; defaults to [contentBackground]
 *   so the preview always blends with the active view
 */
data class ShellChrome(
    val statusBar: Color,
    val topBar: Color = statusBar,
    val contentBackground: Color,
    val miniPlayerBackground: Color = contentBackground
)

/**
 * Stack of [ShellChrome]. Deepest push wins — same dispose semantics as
 * [StatusBarColorStack]. Top-level tabs set the base; album/artist/settings
 * push while composed.
 */
class ShellChromeController(initial: ShellChrome) {
    private val stack: SnapshotStateList<ShellChrome> = mutableStateListOf(initial)
    var current: ShellChrome by mutableStateOf(initial)
        private set

    fun push(chrome: ShellChrome) {
        stack.add(chrome)
        current = chrome
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        current = stack.last()
    }

    fun replaceBase(chrome: ShellChrome) {
        stack[0] = chrome
        if (stack.size == 1) current = chrome
    }
}

val LocalShellChrome = compositionLocalOf<ShellChromeController> {
    error("ShellChromeController not provided")
}

/** Default chrome for Home / Library / My Stuff (surface top bar, bg content). */
@Composable
fun defaultShellChrome(): ShellChrome {
    val c = MaterialTheme.colorScheme
    return ShellChrome(
        statusBar = c.surface,
        topBar = c.surface,
        contentBackground = c.background,
        miniPlayerBackground = c.background
    )
}

/**
 * Push [chrome] for the lifetime of this composition. Status bar is driven from
 * the controller at the root via [ApplyShellChrome].
 */
@Composable
fun ContributeShellChrome(chrome: ShellChrome) {
    val controller = LocalShellChrome.current
    DisposableEffect(chrome) {
        controller.push(chrome)
        onDispose { controller.pop() }
    }
}

/** Apply deepest chrome's status-bar color through the existing stack system. */
@Composable
fun ApplyShellChrome(controller: ShellChromeController) {
    val chrome = controller.current
    ContributeStatusBarColor(chrome.statusBar)
}
