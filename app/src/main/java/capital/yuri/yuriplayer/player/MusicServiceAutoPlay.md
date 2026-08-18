Radio / auto-play architecture
==============================

On-device first. Network only behind explicit opt-in later.

Package: `player/radio/` (aim: extract to shared KMP / offload worker)

- **RadioEngine** — session + dispatch, settings gate (Repeat OFF + auto-play on)
- **RadioPlaybackAlgorithm** — same-artist continuous radio
  - hard cooldown per LP / EP / Single
  - soft weight decay on recent history
  - type weights + weighted random
- **ReleasePoolAlgorithm** — artist/genre pool (playlist radios)
  - `PlaylistRadioSeed.fromTracks()` builds pool from playlist tags
  - `allowExternalFetch` stub (privacy gate closed)
- **ReleaseCatalog** — algorithm-facing library view (swap for remote catalog later)

Queue path
----------
QueueManager.exhaust → MusicServiceAutoPlay.maybePick → RadioEngine →
playSource(album) → AdvanceResult.song → MusicService rebuffers

Playlist radio (API ready, UI later)
------------------------------------
```kotlin
radioEngine.startPlaylistRadio(playlist.tracks)
// exhaust will use RELEASE_POOL with those artists
```

Offload later
-------------
RadioAlgorithm + ReleaseCatalog have no Room/ExoPlayer deps (Log only).
Move package to `:radio-core`; Android app and a self-hosted service both
implement ReleaseCatalog / optional ExternalReleaseSource.
