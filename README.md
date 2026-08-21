# Yuri Player (New Name SoonTM)

A multi-platform Jetpack Compose music player for Android and desktop
(iOS later). Libraries from this device, Jellyfin, and (open)Subsonic.

## Layout

| Module | What |
| --- | --- |
| `:core` | Kotlin Multiplatform. Playback engine SPI, queue host, OS path conventions, local library scan (JVM). |
| `:app` | Android client. |
| `:desktop` | Compose Desktop client (Material 3). LibVLC via vlcj. |

## Desktop

Needs a system LibVLC (`vlc` / `libvlc-dev` on Linux, VideoLAN on Windows,
`brew install libvlc` on macOS).

```bash
./gradlew :desktop:run
```

Default music folders (scanned on start):

- **Linux** — `$XDG_MUSIC_DIR` or `~/Music` (XDG config/cache/data for app files)
- **macOS** — `~/Music` (app files under `~/Library/Application Support/YuriPlayer` and `~/Library/Caches/YuriPlayer`)
- **Windows** — `%USERPROFILE%\Music` (app files under `%APPDATA%\YuriPlayer` and `%LOCALAPPDATA%\YuriPlayer`)

Playback control:

- **Linux** — MPRIS (`playerctl -p yuriplayer play-pause`)
- **Windows** — media keys (`VK_MEDIA_*`)
- **macOS** — Now Playing hook (media keys when accessibility allows)

## Screenshots

todo :P

## License

Licensed under the [AGPLv3](LICENSE).
