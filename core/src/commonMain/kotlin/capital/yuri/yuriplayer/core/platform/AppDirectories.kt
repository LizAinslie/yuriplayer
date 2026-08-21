package capital.yuri.yuriplayer.core.platform

/**
 * OS-conventional locations for Yuri Player.
 *
 * Linux: XDG (`~/.config/yuriplayer`, `~/.cache/yuriplayer`, `~/.local/share/yuriplayer`,
 * `$XDG_MUSIC_DIR` or `~/Music`).
 * macOS: `~/Library/Application Support/YuriPlayer`, `~/Library/Caches/YuriPlayer`, `~/Music`.
 * Windows: `%APPDATA%\YuriPlayer`, `%LOCALAPPDATA%\YuriPlayer\Cache`, `%USERPROFILE%\Music`.
 * Android: app files / cache dirs; default music roots come from the platform scanner.
 */
interface AppDirectories {
    val configDir: String
    val cacheDir: String
    val dataDir: String
    val defaultMusicRoots: List<String>
}

expect fun appDirectories(): AppDirectories
