package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun YuriTheme(
    playerColors: PlayerColors? = null,
    content: @Composable () -> Unit
) {
    val scheme = if (playerColors != null) {
        playerColorScheme(playerColors, YuriDarkColorScheme, useArtBackground = false)
    } else {
        YuriDarkColorScheme
    }
    CompositionLocalProvider(LocalPlayerColors provides playerColors) {
        MaterialTheme(colorScheme = scheme) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scheme.background)
            ) {
                content()
            }
        }
    }
}

@Composable
fun PlayerChromeTheme(
    colors: PlayerColors?,
    useArtBackground: Boolean,
    content: @Composable () -> Unit
) {
    val scheme = if (colors != null) {
        playerColorScheme(colors, MaterialTheme.colorScheme, useArtBackground)
    } else {
        MaterialTheme.colorScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
