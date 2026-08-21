# Bundled LibVLC

Yuri Player ships LibVLC so desktop machines do not need VLC installed.

| Platform | Source |
| --- | --- |
| Windows x64 | Official [VideoLAN.LibVLC.Windows](https://www.nuget.org/packages/VideoLAN.LibVLC.Windows) |
| macOS | Official [VideoLAN.LibVLC.Mac](https://www.nuget.org/packages/VideoLAN.LibVLC.Mac) |
| Linux x64 | Debian `libvlc5` + `libvlccore9` + `vlc-plugin-base` (glibc). Distro VLC is still used if present. |

Natives are **downloaded at build time** (`:desktop:downloadLibVlc`), not committed.

```bash
./gradlew :desktop:downloadLibVlc
./gradlew :desktop:run
```

Override the lookup with `YURI_LIBVLC=/path/to/libvlc` or `-Dyuri.libvlc.dir=...`.

Bundling LibVLC means the desktop distribution is covered by VLC's GPL, which is compatible with this repo's AGPL-3.0.
