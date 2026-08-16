Auto-play recommended (event-driven)
====================================

1. QueueManager.advance() empties queues → QueueEvent.Exhausted
2. QueueEventBridge (Koin createdAtStart) collects queue.events
3. MusicServiceAutoPlay.maybePick() chooses next same-artist album
4. PlayerController.playSource() starts it (binds MusicService if needed)

No callbacks on QueueManager. No MusicService changes required.
