package capital.yuri.yuriplayer.activities.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import capital.yuri.yuriplayer.components.theme.YuriShapes
import capital.yuri.yuriplayer.data.LibrarySettings
import org.koin.compose.koinInject

private val ColorBlackish = Color(0xFF1A1224)

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

@Composable
fun YuriPlayerTheme(
    content: @Composable () -> Unit
) {
    val settings: LibrarySettings = koinInject()
    val colorRev by settings.colorPrefsRevision.collectAsState()
    val context = LocalContext.current
    val scheme = if (colorRev >= 0 &&
        settings.useSystemColors() &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        dynamicDarkColorScheme(context)
    } else {
        YuriDarkPurple
    }

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
        shapes = YuriShapes,
        typography = Typography,
        content = content
    )
}
