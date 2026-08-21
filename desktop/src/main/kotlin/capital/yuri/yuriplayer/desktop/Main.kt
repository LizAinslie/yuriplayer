package capital.yuri.yuriplayer.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.desktop.ui.YuriDesktopApp

fun main() = application {
    val session = remember { DesktopSession() }
    val state = rememberWindowState(width = 980.dp, height = 720.dp)
    Window(
        onCloseRequest = {
            session.release()
            exitApplication()
        },
        title = "Yuri Player",
        state = state
    ) {
        DisposableEffect(Unit) {
            onDispose { session.release() }
        }
        YuriDesktopApp(session)
    }
}
