package capital.yuri.yuriplayer.components.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0E0E12)
private val Panel = Color(0xFF18181F)
private val Accent = Color(0xFFC4B5FD)

@Composable
fun YuriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Ink,
            surface = Panel,
            surfaceVariant = Color(0xFF22222C),
            onBackground = Color(0xFFF4F1FF),
            onSurface = Color(0xFFF4F1FF)
        ),
        content = content
    )
}
