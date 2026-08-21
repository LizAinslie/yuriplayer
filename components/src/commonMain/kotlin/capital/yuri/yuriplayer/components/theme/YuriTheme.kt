package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun YuriTheme(
    colorScheme: ColorScheme = YuriDarkColorScheme,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = YuriShapes
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground
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
