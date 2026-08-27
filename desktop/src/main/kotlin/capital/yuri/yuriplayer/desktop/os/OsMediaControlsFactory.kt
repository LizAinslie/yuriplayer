package capital.yuri.yuriplayer.desktop.os

import capital.yuri.yuriplayer.core.os.NoOpMediaControls
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.platform.HostOs
import capital.yuri.yuriplayer.core.platform.hostOs
import capital.yuri.yuriplayer.desktop.os.mac.MacNowPlayingControls

fun createOsMediaControls(): OsMediaControls = when (hostOs()) {
    // dev.toastbits:mediasession handles both MPRIS (Linux) and SMTC (Windows).
    HostOs.LINUX, HostOs.WINDOWS -> MediasessionControls()
    // The library returns null on macOS; keep the JNA Now Playing bridge.
    HostOs.MACOS -> MacNowPlayingControls()
    else -> NoOpMediaControls
}
