# AGENTS.md — Yuri Player

Guidance for AI coding agents (and humans) working in this repository.

## Project

**Yuri Player** — a free, open-source (AGPL-3.0) music player built with
**Jetpack Compose / Compose Multiplatform**. Targets: **Android** (shipping),
**desktop** (shipping), **iOS** (planned, Darwin targets stubbed). Sources:
local files, **Jellyfin**, and **Subsonic**/**Navidrome**.

- Package base: `capital.yuri.yuriplayer`
- Application id: `capital.yuri.yuriplayer`
- Repo: `https://github.com/LizAinslie/yuriplayer`
- License: [AGPL-3.0](LICENSE)

## Module Layout

| Module | Type | Responsibility |
| --- | --- | --- |
| `:core` | Kotlin Multiplatform (`commonMain`/`androidMain`/`jvmMain`) | Platform-agnostic domain: `PlaybackEngine` SPI, `PlayerSession` queue host, `Track` model, `OsMediaControls` SPI, path conventions, logging DSL. |
| `:components` | Kotlin Multiplatform (Compose) | Shared Compose UI: cover art, album/artist pages, player bar, now-playing sidebar, context menus, theme. |
| `:app` | Android application | Android client: activities, services, Koin DI, Room DB, Media3 + LibVLC engines, Jellyfin/Subsonic clients, metadata enrichment. |
| `:desktop` | Compose Desktop (JVM) | Desktop client (Material 3). LibVLC via vlcj. Manual DI (no Koin). OS media-key/now-playing integrations. |
| `:mediasession` | Kotlin Multiplatform (JVM target) | **Vendored fork** of `dev.toastbits:mediasession` (0.1.1): Linux MPRIS + Windows SMTC, with upstream bugs fixed (see MPRIS section). |

### Source-set conventions

- `commonMain` — shared logic/UI, no platform APIs. Use `expect`/`actual` for
  anything platform-specific.
- `androidMain` / `jvmMain` — platform `actual`s.
- `:core` compiles to `android` (minSdk 27, compileSdk 37) and `jvm`
  (JVM 11 target). `:components` mirrors this.
- Android app code lives in `app/src/main/java/...` (not `kotlin/`).

## Build

Toolchain: **Kotlin 2.3.21**, **AGP 9.3.1**, **Compose Multiplatform 1.8.2**,
Gradle with **configuration cache enabled** (`org.gradle.configuration-cache=true`).

Common commands:

```bash
./gradlew :app:assembleDebug                 # build Android APK
./gradlew :desktop:run                       # run desktop app
./gradlew :desktop:packageDeb                # Linux .deb (Depends: vlc)
./gradlew :desktop:packageRpm
./gradlew :desktop:packageDistributionForCurrentOS
./gradlew :core:jvmTest :components:jvmTest  # shared unit tests
./gradlew :core:compileKotlinJvm :desktop:compileKotlin
```

### Configuration-cache rules

CC is on, so **build scripts must be configuration-cache compatible**:

- No bare `ProcessBuilder`/`exec` at configuration time. Use a Gradle
  `ValueSource` with injected `ExecOperations` (see `GitCommandValueSource` in
  `app/build.gradle.kts`).
- Git metadata (commit, branch, tag, dirty) is read via `ValueSource` and
  injected as `BuildConfig` fields, not via `System.getProperty`.

## Architecture

### Playback: engines vs. session

- **`PlaybackEngine`** (`:core` `commonMain`) is the *audio backend only* — a
  small SPI: `load/play/pause/stop/seekTo`, position/duration, volume, listener
  callbacks. It does **not** own queue/identity.
  - Android impls: `Media3PlaybackEngine`, `VlcPlaybackEngine`
    (`app/.../player/engine/`), selected at runtime by `PlaybackEngineFactory`
    (Settings → `PlaybackEngineId.MEDIA3|VLC|FFMPEG`). `FFMPEG` is reserved,
    not wired.
  - Desktop impl: `VlcjPlaybackEngine` (`:desktop`), LibVLC via vlcj.
- **`PlayerSession`** (`:core` `commonMain`) is the *host-side* queue + now-playing.
  Hot lane (user "add to queue") + cold lane (album/playlist/search context),
  shuffle, repeat, history, volume. Engines only produce sound; the session
  owns identity.

Android wraps the session in `player/MusicService.kt` (foreground service,
Media3 session/MediaStyle notification) plus `QueueManager`, `PlayerController`,
`PlaybackStateStore`, `PlaybackHistoryStore`.

### OS media integration

- **`OsMediaControls`** SPI (`:core` `commonMain/os/OsMediaControls.kt`) abstracts
  system "now playing" + media keys: `attach/update/release` + `Callbacks`.
- Desktop impls (`:desktop` `os/`), selected by `createOsMediaControls()`:
  - `MediasessionControls` — Linux MPRIS + Windows SMTC via
    `dev.toastbits:mediasession` (wraps dbus-java internally, so no raw
    `Variant` marshalling in-app).
  - `mac/MacNowPlayingControls` — macOS Now Playing + media keys (JNA stubs);
    the library returns `null` on macOS so this stays.
  - `win/WindowsSmtcControls` — legacy media-keys impl (`RegisterHotKey`), now
    superseded by `MediasessionControls` and left unused.
  - `linux/MprisMediaControls` — the old hand-rolled dbus-java MPRIS impl, now
    fully commented out and superseded by `MediasessionControls`.

### Dependency injection

- **Android** uses **Koin** (`app/di/AppModule.kt`, `koin-android`,
  `koin-androidx-compose`). All repositories, clients, stores, and the DB are
  wired there.
- **Desktop** uses **manual constructor injection** — `DesktopSession` is built
  with `remember { DesktopSession() }` in `desktop/Main.kt` and wires its own
  stores/engines. No Koin on the JVM/desktop side.

### Data layer (Android)

- **Room** for persistence (`YuriDatabase`; KSP compiler). Schema dir is
  `app/schemas/`. See the `room { schemaDirectory(...) }` block.
- **Library sources** are behind `LibrarySource` / `LibrarySourceFactory` /
  `LibrarySourceRegistry` (`app/.../data/source/`): `LocalLibrarySource`,
  `JellyfinClient`, `SubsonicClient`, plus artist-image/metadata providers
  (MusicBrainz, Discogs, Deezer, AudioDB, Bandsintown, Wikipedia, Wikidata).
- **Metadata enrichment / tag editing** via jaudiotagger (`TagWriter.kt`,
  `MetadataEditService`, `TagWritability`).
- **FFmpeg** is bundled per-ABI (arm64-v8a, armeabi-v7a, x86_64) under
  `app/src/main/assets/ffmpeg/`, built by `native/ffmpeg/build.sh` (NDK
  cross-compile, wired to the `buildFfmpeg` Gradle task).

### UI / Compose

- Shared UI lives in `:components` under `components/` (album, artist, art,
  dialog, layout, list, menu, model, player, settings, theme).
- **Context menus** use the `buildContextMenu { }` DSL (`components/menu/MenuDsl.kt`)
  with `item/submenu/divider`. Submenu hover uses a **prediction cone**
  (`components/menu/PredictionCone.kt`) — the Amazon/macOS triangle heuristic so
  the pointer can travel diagonally into an open submenu without losing the
  highlighted parent.
- Desktop theming/dark-mode uses `components/theme` + a manual `Choice` model
  (system/light/dark); window background is set from `session.theme.choice`.

## Conventions

### Logging

Use the `yuriLog` DSL (`:core` `commonMain/log/YuriLog.kt`). **Never** `println`
or `Log.d` directly.

```kotlin
val log = yuriLog("Vlcj")          // short tag; platforms prefix "YuriPlayer."
log.i { "play ${redactSecrets(mrl)}" }
log.e(t) { "stream failed" }
```

- Tags are short (`Vlcj`, `Queue`, `Index`, `Mpris`). The `YuriPlayer.` prefix
  is normalized away, so `yuriLog("YuriPlayer.Radio")` == `yuriLog("Radio")`.
- Desktop → SLF4J/Logback, Android → logcat, Darwin → `os_log` (later).
- **Always** wrap URLs/tokens in `redactSecrets(...)` before logging — it strips
  query secrets (`t`, `s`, `api_key`, `access_token`, `password`, …) and
  `Authorization:` headers.

### `expect`/`actual` helpers (already provided)

- `appDirectories(): AppDirectories` — OS-conventional config/cache/data dirs +
  default music roots.
- `hostOs(): HostOs` — `ANDROID | LINUX | MACOS | WINDOWS | UNKNOWN`.
- `platformLog` / `platformLogEnabled` — logging backend.

## Testing

- **Unit tests:** `:core` and `:components` have `jvmTest` source sets
  (`kotlin("test")`). Android has `test`/`androidTest` (Robolectric + Espresso +
  Compose UI tests).
- **Maestro** E2E flows live in `maestro/` (`smoke.yaml`, `skip.yaml`,
  `engine-vlc.yaml`, `engine-media3.yaml`). They target test ids
  (`tab_my_stuff`, `mini_player`, `np_skip_next`, `settings_playback_engine`, …)
  and are **on-device smoke tests** that keep user library/logins
  (`clearState: false`).
- **CI** (`.github/workflows/ci.yml`): JDK 21 + Android SDK 37, runs
  `:core:jvmTest`, `:core:compileKotlinJvm`, `:components:compileKotlinJvm`,
  `:desktop:compileKotlin`, `:app:assembleDebug`.

## Platform notes & gotchas

### LibVLC (desktop)

- `:desktop` bundles LibVLC for **Windows/macOS** installers; **Linux** `.deb`/
  `.rpm` **depend on the distro `vlc`** package instead (see
  `desktop/libvlc/README.md` and the `downloadLibVlc` / `copyLibVlcResources`
  tasks in `desktop/build.gradle.kts`).
- `VlcjPlaybackEngine.FACTORY_ARGS` passes `--no-http-h2` because **VLC's h2
  stack breaks HTTPS streams** (Navidrome/Jellyfin) with "peer stream 1 error:
  Protocol error". If a distro's older `vlc` rejects `--no-http-h2` ("unknown
  option"), the bundled LibVLC version must be raised to match, or the flag
  made conditional on the runtime VLC version.

### MPRIS / dbus-java (Linux)

- Linux MPRIS and Windows SMTC are handled by **`dev.toastbits:mediasession`**
  (`MediasessionControls`). It wraps dbus-java internally, so the old
  `Variant(...)` collection-type foot-gun (`Collections$SingletonList`,
  `ArrayList`) is not something app code touches anymore.
- The library only ships a JVM artifact (`mediasession-jvm`); it returns `null`
  on macOS and has no Android target. Its dbus-java transitive deps are on the
  **runtime** classpath, so `:desktop` must not import `org.freedesktop.dbus.*`
  directly.
- `linux/MprisMediaControls.kt` is the retired hand-rolled MPRIS impl
  (commented out) kept only for reference.
- Known quirk: the library names its `Seek`/`SetPosition` callback params in
  milliseconds (`by_ms`/`to_ms`) but does not convert the incoming MPRIS
  microsecond offsets — treat seek offsets as ms for now.

### Android storage permissions

- The manifest uses `READ_MEDIA_AUDIO`, `READ/WRITE_EXTERNAL_STORAGE` (≤32),
  and `MANAGE_EXTERNAL_STORAGE` (30+) — justified for scanning the user's music
  and **rewriting embedded tags** (title/artist/cover) in-place under `Music/`.
  Keep those `tools:ignore="ScopedStorage"` justifications if you touch them.

### Signing / secrets

- `keystore.properties`, `*.jks`, `*.keystore` are git-ignored. Release signing
  only activates when `keystore.properties` exists. Never commit secrets.

## Style

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent.
- Package structure mirrors feature area, not layer (e.g. `player/`,
  `data/source/`, `components/menu/`).
- Prefer `data class` + `StateFlow` for observable UI state; `expect`/`actual`
  for platform seams; short, focused files with KDoc on non-obvious types
  (especially the SPI boundaries in `:core`).
