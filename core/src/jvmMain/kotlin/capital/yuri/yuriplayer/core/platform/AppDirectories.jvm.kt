package capital.yuri.yuriplayer.core.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val APP_ID = "yuriplayer"
private const val APP_NAME = "YuriPlayer"

class JvmAppDirectories(
    private val os: HostOs = hostOs()
) : AppDirectories {

    private val home: String
        get() = System.getProperty("user.home") ?: "."

    override val configDir: String
        get() = when (os) {
            HostOs.LINUX, HostOs.UNKNOWN ->
                xdg("XDG_CONFIG_HOME", ".config") + File.separator + APP_ID
            HostOs.MACOS ->
                macLibrary("Application Support")
            HostOs.WINDOWS ->
                winAppData()
            HostOs.ANDROID -> error("use AndroidAppDirectories")
        }.also { File(it).mkdirs() }

    override val cacheDir: String
        get() = when (os) {
            HostOs.LINUX, HostOs.UNKNOWN ->
                xdg("XDG_CACHE_HOME", ".cache") + File.separator + APP_ID
            HostOs.MACOS ->
                macLibrary("Caches")
            HostOs.WINDOWS ->
                winLocalAppData() + File.separator + "Cache"
            HostOs.ANDROID -> error("use AndroidAppDirectories")
        }.also { File(it).mkdirs() }

    override val dataDir: String
        get() = when (os) {
            HostOs.LINUX, HostOs.UNKNOWN ->
                xdg("XDG_DATA_HOME", ".local/share") + File.separator + APP_ID
            HostOs.MACOS ->
                macLibrary("Application Support")
            HostOs.WINDOWS ->
                winLocalAppData()
            HostOs.ANDROID -> error("use AndroidAppDirectories")
        }.also { File(it).mkdirs() }

    override val defaultMusicRoots: List<String>
        get() = when (os) {
            HostOs.LINUX, HostOs.UNKNOWN -> linuxMusicRoots()
            HostOs.MACOS -> listOfNotNull(existing(File(home, "Music")))
            HostOs.WINDOWS -> windowsMusicRoots()
            HostOs.ANDROID -> emptyList()
        }

    private fun xdg(env: String, fallbackUnderHome: String): String {
        val value = System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }
        return value ?: (home + File.separator + fallbackUnderHome.replace('/', File.separatorChar))
    }

    private fun macLibrary(folder: String): String =
        listOf(home, "Library", folder, APP_NAME).joinToString(File.separator)

    private fun winAppData(): String {
        val roaming = System.getenv("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
        return (roaming ?: (home + File.separator + "AppData" + File.separator + "Roaming")) +
            File.separator + APP_NAME
    }

    private fun winLocalAppData(): String {
        val local = System.getenv("LOCALAPPDATA")?.trim()?.takeIf { it.isNotEmpty() }
        return (local ?: (home + File.separator + "AppData" + File.separator + "Local")) +
            File.separator + APP_NAME
    }

    private fun linuxMusicRoots(): List<String> {
        val fromXdg = readXdgMusicDir()
        val homeMusic = File(home, "Music").absolutePath
        return listOfNotNull(fromXdg, homeMusic).distinct().filter { File(it).isDirectory }
    }

    private fun readXdgMusicDir(): String? {
        val env = System.getenv("XDG_MUSIC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
        if (env != null) return expandHome(env)
        val dirsFile = File(xdg("XDG_CONFIG_HOME", ".config"), "user-dirs.dirs")
        if (!dirsFile.isFile) return null
        dirsFile.useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (!t.startsWith("XDG_MUSIC_DIR")) continue
                val raw = t.substringAfter('=').trim().trim('"')
                return expandHome(raw)
            }
        }
        return null
    }

    private fun windowsMusicRoots(): List<String> {
        val userMusic = System.getenv("USERPROFILE")?.let { File(it, "Music") }
            ?: File(home, "Music")
        val publicMusic = System.getenv("PUBLIC")?.let { File(it, "Music") }
        return listOfNotNull(existing(userMusic), publicMusic?.let { existing(it) })
    }

    private fun expandHome(raw: String): String {
        if (raw.startsWith("\$HOME")) return home + raw.removePrefix("\$HOME")
        if (raw.startsWith("~")) return home + raw.substring(1)
        return raw
    }

    private fun existing(file: File): String? =
        file.absolutePath.takeIf { file.isDirectory }

    companion object {
        fun ensure(path: String): Path {
            val p = Paths.get(path)
            Files.createDirectories(p)
            return p
        }
    }
}

private val defaultDirs = JvmAppDirectories()

actual fun appDirectories(): AppDirectories = defaultDirs
