package capital.yuri.yuriplayer.activities.ui

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tints the status bar to [color] while this composition is active.
 * Works on API 27 (window statusBarColor) — not ROM Material You,
 * just classic system bar coloring + light/dark icon contrast.
 */
@Composable
fun ThemedStatusBar(color: Color, enabled: Boolean = true) {
    val view = LocalView.current
    if (!enabled || view.isInEditMode) return

    DisposableEffect(color, enabled) {
        val activity = view.context as? Activity
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val previous = window.statusBarColor
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLight = controller.isAppearanceLightStatusBars

            window.statusBarColor = color.toArgb()
            controller.isAppearanceLightStatusBars = color.luminance() > 0.5f

            onDispose {
                window.statusBarColor = previous
                controller.isAppearanceLightStatusBars = previousLight
            }
        }
    }

    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = color.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
            color.luminance() > 0.5f
    }
}

fun defaultStatusBarColor(): Int = AndroidColor.TRANSPARENT
