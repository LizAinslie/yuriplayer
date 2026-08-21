package capital.yuri.yuriplayer.core.platform

enum class HostOs {
    ANDROID,
    LINUX,
    MACOS,
    WINDOWS,
    UNKNOWN
}

expect fun hostOs(): HostOs
