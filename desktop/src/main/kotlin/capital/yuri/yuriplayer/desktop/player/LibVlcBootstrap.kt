package capital.yuri.yuriplayer.desktop.player

import capital.yuri.yuriplayer.core.log.yuriLog
import java.io.File

/**
 * Points JNA / LibVLC at a bundled copy before [uk.co.caprica.vlcj.factory.MediaPlayerFactory]
 * loads. Order: `-Dyuri.libvlc.dir`, `YURI_LIBVLC`, Compose app-resources, then system VLC.
 *
 * Windows/macOS natives come from VideoLAN's official LibVLC NuGet packages.
 * Linux uses extracted Debian libvlc + plugins (glibc) as a fallback; distro
 * packages are still preferred when present.
 */
object LibVlcBootstrap {
    private val log = yuriLog("LibVlc")

    fun install(): File? {
        val dir = resolveDir() ?: return null
        val path = dir.canonicalPath
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) path else "$path${File.pathSeparator}$existing"
        )
        val plugins = File(dir, "plugins")
        if (plugins.isDirectory) {
            System.setProperty("VLC_PLUGIN_PATH", plugins.canonicalPath)
        }
        log.w { "LibVLC bundle: $path" }
        return dir
    }

    private fun resolveDir(): File? {
        val candidates = listOfNotNull(
            System.getProperty("yuri.libvlc.dir"),
            System.getenv("YURI_LIBVLC"),
            System.getProperty("compose.application.resources.dir")
        )
        return candidates
            .map(::File)
            .firstOrNull { hasLibvlc(it) }
    }

    private fun hasLibvlc(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val names = dir.list()?.toList().orEmpty()
        return names.any { it.startsWith("libvlc") && (it.endsWith(".dll") || it.endsWith(".so") || it.endsWith(".dylib") || it.contains(".so.")) }
    }
}
