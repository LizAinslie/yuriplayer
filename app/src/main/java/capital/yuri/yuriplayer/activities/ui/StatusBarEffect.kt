package capital.yuri.yuriplayer.activities.ui

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Stack of status-bar colors. The top (deepest) entry wins.
 * Screens push while composed and pop on dispose, so navigating
 * album → library or now-playing → album always leaves the right tint.
 */
class StatusBarColorStack(initial: Color) {
    private val colors: SnapshotStateList<Color> = mutableStateListOf(initial)
    var current: Color by mutableStateOf(initial)
        private set

    fun push(color: Color) {
        colors.add(color)
        current = color
    }

    fun pop() {
        if (colors.size > 1) colors.removeAt(colors.lastIndex)
        current = colors.last()
    }

    fun replaceBase(color: Color) {
        colors[0] = color
        if (colors.size == 1) current = color
    }
}

val LocalStatusBarStack = compositionLocalOf<StatusBarColorStack> {
    error("StatusBarColorStack not provided")
}

@Composable
fun ContributeStatusBarColor(color: Color, enabled: Boolean = true) {
    if (!enabled) return
    val stack = LocalStatusBarStack.current
    DisposableEffect(color) {
        stack.push(color)
        onDispose { stack.pop() }
    }
}

/**
 * Applies [stack].current to the window status bar + icon contrast.
 *
 * Always disables the platform status-bar contrast scrim (the gray wash over
 * edge-to-edge content on API 29+). Transparent colors stay fully clear.
 */
@Composable
fun ApplyStatusBarStack(stack: StatusBarColorStack) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val color = stack.current
    // Fully transparent → treat as dark content behind icons (white icons)
    val lightIcons = color.alpha > 0.5f && color.luminance() > 0.5f

    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        // Kill the gray “contrast” overlay Android draws over transparent bars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = color.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightIcons
    }
}

@Composable
fun ThemedStatusBar(color: Color, enabled: Boolean = true) {
    val stack = runCatching { LocalStatusBarStack.current }.getOrNull()
    if (stack != null) {
        ContributeStatusBarColor(color, enabled)
        return
    }

    val view = LocalView.current
    if (!enabled || view.isInEditMode) return
    val lightIcons = color.alpha > 0.5f && color.luminance() > 0.5f

    DisposableEffect(color, enabled) {
        val activity = view.context as? Activity
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val previous = window.statusBarColor
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLight = controller.isAppearanceLightStatusBars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            window.statusBarColor = color.toArgb()
            controller.isAppearanceLightStatusBars = lightIcons
            onDispose {
                window.statusBarColor = previous
                controller.isAppearanceLightStatusBars = previousLight
            }
        }
    }

    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = color.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightIcons
    }
}
