Auto-play recommended
=====================

Flow:
1. QueueManager.advance() runs out of tracks → emits QueueEvent.Exhausted
2. MusicService collects queueManager.events
3. On Exhausted + setting on + RepeatMode.OFF → MusicServiceAutoPlay.maybePick()
4. If pick ≠ null → MusicService.playSource(album songs, source)

No DI callbacks. Decision logic stays in MusicServiceAutoPlay / ArtistRadio;
playback loading stays in MusicService.
