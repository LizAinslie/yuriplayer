package capital.yuri.yuriplayer.components.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Same tokens as Android [capital.yuri.yuriplayer.activities.ui.theme]. */
val YuriPurple = Color(0xFFB388FF)
val YuriPurpleDim = Color(0xFF7E57C2)
val YuriBg = Color(0xFF121018)
val YuriSurface = Color(0xFF1C1826)
val YuriSurfaceVariant = Color(0xFF2A2438)
val YuriOnBg = Color(0xFFE8E0F0)
val YuriMuted = Color(0xFFB0A4C0)
val YuriBlackish = Color(0xFF1A1224)
val YuriPurpleGrey = Color(0xFFCCC2DC)
val YuriPink = Color(0xFFEFB8C8)

val YuriDarkColorScheme = darkColorScheme(
    primary = YuriPurple,
    onPrimary = YuriBlackish,
    primaryContainer = YuriPurpleDim,
    onPrimaryContainer = YuriOnBg,
    secondary = YuriPurpleGrey,
    onSecondary = YuriBlackish,
    tertiary = YuriPink,
    onTertiary = YuriBlackish,
    background = YuriBg,
    onBackground = YuriOnBg,
    surface = YuriSurface,
    onSurface = YuriOnBg,
    surfaceVariant = YuriSurfaceVariant,
    onSurfaceVariant = YuriMuted,
    outline = YuriMuted.copy(alpha = 0.5f)
)
