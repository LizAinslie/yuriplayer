Auto-play recommended (event-driven)
====================================

1. QueueManager.advance() empties hot+cold → emits QueueEvent.Exhausted
2. QueueEventBridge collects queue.events (createdAtStart in Koin)
3. MusicServiceAutoPlay.maybePick() decides next album
4. Bridge calls MusicService.playSource via playSourceHandler

MusicService.onCreate registers the handler; onDestroy clears it.
No labeled returns / DI callback properties.
