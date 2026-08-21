package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun YuriTheme(content: @Composable () -> Unit) {
    val scheme = YuriDarkColorScheme
    MaterialTheme(colorScheme = scheme) {
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
    MaterialTheme(colorScheme = scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = scheme.onBackground,
            content = content
        )
    }
}
