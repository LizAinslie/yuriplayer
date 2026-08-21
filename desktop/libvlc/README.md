# LibVLC on desktop

| Ship as | LibVLC |
| --- | --- |
| Windows `.msi` / `.exe` | Bundled official [VideoLAN.LibVLC.Windows](https://www.nuget.org/packages/VideoLAN.LibVLC.Windows) in app resources |
| macOS `.dmg` / `.pkg` | Bundled official [VideoLAN.LibVLC.Mac](https://www.nuget.org/packages/VideoLAN.LibVLC.Mac) in app resources |
| Linux `.deb` / `.rpm` | **Depends on distro `vlc`** (libvlc + plugins from the OS). Not vendored. |

Natives for Windows/macOS are downloaded at package time (`:desktop:downloadLibVlc`), not committed.

```bash
# Linux packages (this host) — requires system VLC at runtime
./gradlew :desktop:packageDeb
./gradlew :desktop:packageRpm

# Windows/macOS natives into the installer (on those OSes, or with -Pyuri.libvlc.all=true)
./gradlew :desktop:packageMsi
./gradlew :desktop:packageDmg
```

`:desktop:run` on Linux uses NativeDiscovery against `/usr/lib`. Install `vlc` or `libvlc-dev` + `vlc-plugin-base`.

Override lookup with `YURI_LIBVLC=/path/to/libvlc` or `-Dyuri.libvlc.dir=...`.

Windows/macOS installers that bundle LibVLC are covered by VLC's GPL, which is compatible with this repo's AGPL-3.0.
