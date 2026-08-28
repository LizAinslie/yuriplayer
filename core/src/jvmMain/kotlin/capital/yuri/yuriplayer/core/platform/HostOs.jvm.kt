package capital.yuri.yuriplayer.core.platform

actual fun hostOs(): HostOs {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        name.contains("linux") -> HostOs.LINUX
        name.contains("mac") || name.contains("darwin") -> HostOs.MACOS
        name.contains("win") -> HostOs.WINDOWS
        else -> HostOs.UNKNOWN
    }
}
