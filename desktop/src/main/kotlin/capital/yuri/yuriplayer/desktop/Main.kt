package capital.yuri.yuriplayer.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.desktop.ui.YuriDesktopApp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() = application {
    val dirs = appDirectories()
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(HttpClient(CIO))) }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(dirs.cacheDir, "coil").toOkioPath())
                    .build()
            }
            .build()
    }
    val session = remember { DesktopSession() }
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
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
