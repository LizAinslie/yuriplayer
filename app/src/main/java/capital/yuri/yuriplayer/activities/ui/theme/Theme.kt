package capital.yuri.yuriplayer.activities.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ColorBlackish = androidx.compose.ui.graphics.Color(0xFF1A1224)

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

@Composable
fun YuriPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = YuriDarkPurple,
        typography = Typography,
        content = content
    )
}
