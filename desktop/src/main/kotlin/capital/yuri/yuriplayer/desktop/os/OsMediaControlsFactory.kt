package capital.yuri.yuriplayer.desktop.os

import capital.yuri.yuriplayer.core.os.NoOpMediaControls
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.platform.HostOs
import capital.yuri.yuriplayer.core.platform.hostOs
import capital.yuri.yuriplayer.desktop.os.linux.MprisMediaControls
import capital.yuri.yuriplayer.desktop.os.mac.MacNowPlayingControls
import capital.yuri.yuriplayer.desktop.os.win.WindowsSmtcControls

fun createOsMediaControls(): OsMediaControls = when (hostOs()) {
    HostOs.LINUX -> MprisMediaControls()
    HostOs.MACOS -> MacNowPlayingControls()
    HostOs.WINDOWS -> WindowsSmtcControls()
    else -> NoOpMediaControls
}
