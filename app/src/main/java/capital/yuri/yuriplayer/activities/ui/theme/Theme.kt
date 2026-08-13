package capital.yuri.yuriplayer.activities.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Always-dark purple theme. Accent customization lands in Settings later. */
private val YuriDarkPurple = darkColorScheme(
    primary = YuriPurple,
    onPrimary = ColorBlackish,
    primaryContainer = YuriPurpleDim,
    onPrimaryContainer = YuriOnBg,
    secondary = PurpleGrey80,
    onSecondary = ColorBlackish,
    tertiary = Pink80,
    onTertiary = ColorBlackish,
    background = YuriBg,
    onBackground = YuriOnBg,
    surface = YuriSurface,
    onSurface = YuriOnBg,
    surfaceVariant = YuriSurfaceVariant,
    onSurfaceVariant = YuriMuted,
    outline = YuriMuted.copy(alpha = 0.5f)
)

private val ColorBlackish = Color(0xFF1A1224)

@Composable
fun YuriPlayerTheme(
    content: @Composable () -> Unit
) {
    val scheme = YuriDarkPurple

    // Keep system status-bar *icons* readable against the app chrome.
    // Light theme later: same rule (luminance of surface) flips icons automatically.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            val barColor = scheme.surface
            window.statusBarColor = barColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                barColor.luminance() > 0.5f
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        content = content
    )
}
