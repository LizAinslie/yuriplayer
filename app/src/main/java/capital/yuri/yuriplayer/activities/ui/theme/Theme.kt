package capital.yuri.yuriplayer.activities.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import capital.yuri.yuriplayer.components.theme.ThemeChoice
import capital.yuri.yuriplayer.components.theme.YuriShapes
import capital.yuri.yuriplayer.components.theme.colorScheme
import capital.yuri.yuriplayer.components.theme.isDark
import capital.yuri.yuriplayer.data.LibrarySettings
import org.koin.compose.koinInject

@Composable
fun YuriPlayerTheme(
    content: @Composable () -> Unit
) {
    val settings: LibrarySettings = koinInject()
    val colorRev by settings.colorPrefsRevision.collectAsState()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val choice = ThemeChoice(
        mode = settings.getThemeMode(),
        accentId = settings.getAccentId()
    )
    val dark = choice.isDark(systemDark)
    val dynamic = if (colorRev >= 0 &&
        settings.useSystemColors() &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
    val scheme = choice.colorScheme(systemDark, dynamic)

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
