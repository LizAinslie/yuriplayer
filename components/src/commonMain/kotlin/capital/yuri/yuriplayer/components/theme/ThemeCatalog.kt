package capital.yuri.yuriplayer.components.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Appearance is **not** glued to Material 3 forever.
 *
 * [ThemeFamily.MATERIAL3] is the default (light/dark + accent). Other families
 * (Jewel, macOS, Windows) plug in later — see github.com/LizAinslie/yuriplayer/issues/16.
 */
enum class ThemeFamily(val id: String, val label: String) {
    MATERIAL3("material3", "Material 3")
}

enum class ThemeMode(val id: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SYSTEM("system", "Follow system");

    companion object {
        fun fromId(raw: String?): ThemeMode =
            entries.firstOrNull { it.id.equals(raw, true) } ?: DARK
    }
}

data class AccentSwatch(
    val id: String,
    val label: String,
    val color: Color
)

object AccentCatalog {
    val yuri = AccentSwatch("yuri", "Yuri purple", Color(0xFFB388FF))
    val all: List<AccentSwatch> = listOf(
        yuri,
        AccentSwatch("violet", "Violet", Color(0xFF7C4DFF)),
        AccentSwatch("rose", "Rose", Color(0xFFFF8A80)),
        AccentSwatch("coral", "Coral", Color(0xFFFF6E40)),
        AccentSwatch("amber", "Amber", Color(0xFFFFC107)),
        AccentSwatch("lime", "Lime", Color(0xFFC6FF00)),
        AccentSwatch("teal", "Teal", Color(0xFF1DE9B6)),
        AccentSwatch("sky", "Sky", Color(0xFF40C4FF)),
        AccentSwatch("indigo", "Indigo", Color(0xFF5C6BC0)),
        AccentSwatch("graphite", "Graphite", Color(0xFF90A4AE))
    )

    fun byId(id: String?): AccentSwatch =
        all.firstOrNull { it.id.equals(id, true) } ?: yuri
}

data class ThemeChoice(
    val mode: ThemeMode = ThemeMode.DARK,
    val accentId: String = AccentCatalog.yuri.id,
    val family: ThemeFamily = ThemeFamily.MATERIAL3
)

fun ThemeChoice.isDark(systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemDark
}

fun ThemeChoice.colorScheme(
    systemDark: Boolean,
    dynamic: ColorScheme? = null
): ColorScheme {
    if (dynamic != null && family == ThemeFamily.MATERIAL3) return dynamic
    val accent = AccentCatalog.byId(accentId).color
    return material3Scheme(isDark(systemDark), accent)
}

fun material3Scheme(dark: Boolean, accent: Color): ColorScheme {
    val onAccent = if (accent.luminance() > 0.55f) Color(0xFF1A1224) else Color.White
    return if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.28f),
            onPrimaryContainer = Color(0xFFE8E0F0),
            secondary = accent.copy(alpha = 0.85f),
            onSecondary = onAccent,
            tertiary = Color(0xFFEFB8C8),
            onTertiary = Color(0xFF1A1224),
            background = Color(0xFF121018),
            onBackground = Color(0xFFE8E0F0),
            surface = Color(0xFF1C1826),
            onSurface = Color(0xFFE8E0F0),
            surfaceVariant = Color(0xFF2A2438),
            onSurfaceVariant = Color(0xFFB0A4C0),
            outline = Color(0xFFB0A4C0).copy(alpha = 0.5f)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.18f),
            onPrimaryContainer = Color(0xFF1A1224),
            secondary = accent,
            onSecondary = onAccent,
            tertiary = Color(0xFFB0005A),
            onTertiary = Color.White,
            background = Color(0xFFF6F0FA),
            onBackground = Color(0xFF1A1224),
            surface = Color(0xFFFFFBFF),
            onSurface = Color(0xFF1A1224),
            surfaceVariant = Color(0xFFE8DFF0),
            onSurfaceVariant = Color(0xFF4A4258),
            outline = Color(0xFF7A7088)
        )
    }
}
