package capital.yuri.yuriplayer.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import capital.yuri.yuriplayer.components.theme.isDark
import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.desktop.player.LibVlcBootstrap
import capital.yuri.yuriplayer.desktop.ui.YuriDesktopApp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    LibVlcBootstrap.install()
    application {
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
            state = state,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay -> {
                        session.player.togglePlay(); true
                    }
                    Key.MediaPause -> {
                        session.player.pause(); true
                    }
                    Key.MediaStop -> {
                        session.player.stop(); true
                    }
                    Key.MediaNext, Key.MediaSkipForward -> {
                        session.player.next(); true
                    }
                    Key.MediaPrevious, Key.MediaSkipBackward -> {
                        session.player.previous(); true
                    }
                    Key.VolumeUp -> {
                        session.player.setVolume(session.player.volume.value + 0.05f); true
                    }
                    Key.VolumeDown -> {
                        session.player.setVolume(session.player.volume.value - 0.05f); true
                    }
                    Key.VolumeMute -> {
                        val v = session.player.volume.value
                        session.player.setVolume(if (v > 0f) 0f else 1f); true
                    }
                    else -> false
                }
            }
        ) {
            window.background = java.awt.Color(0x12, 0x10, 0x18)
            session.onRaise = {
                window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            DisposableEffect(Unit) {
                onDispose { session.release() }
            }
            YuriDesktopApp(session)
            val choice by session.theme.choice.collectAsState()
            val dark = choice.isDark(isSystemInDarkTheme())
            window.background = if (dark) java.awt.Color(0x12, 0x10, 0x18)
            else java.awt.Color(0xF6, 0xF0, 0xFA)
        }
    }
}
