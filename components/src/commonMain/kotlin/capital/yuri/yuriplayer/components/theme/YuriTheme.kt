package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun YuriTheme(
    choice: ThemeChoice = ThemeChoice(),
    colorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val scheme = colorScheme ?: choice.colorScheme(isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = scheme,
        shapes = YuriShapes
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = scheme.onBackground
        ) {
            content()
        }
    }
}

@Composable
fun PlayerChromeTheme(
    colors: PlayerColors?,
    useArtBackground: Boolean,
    content: @Composable () -> Unit
) {
    val base = MaterialTheme.colorScheme
    val scheme = if (colors != null) {
        playerColorScheme(colors, base, useArtBackground)
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, shapes = YuriShapes, content = content)
}
