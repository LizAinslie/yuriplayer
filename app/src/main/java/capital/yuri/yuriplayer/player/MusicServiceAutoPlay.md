Auto-play recommended
=====================

Requirements
------------
1. Settings → Playback → **Auto-play recommended** ON
2. Repeat mode must be **OFF** (not one, not all/cold)
3. Library must contain **another** album by the same artist

How it works
------------
When QueueManager.advance() empties hot+cold queues:
1. Emits QueueEvent.Exhausted (logged by QueueEventBridge)
2. MusicServiceAutoPlay.maybePick() chooses a random other album
3. QueueManager.playSource(that album) runs inside advance
4. AdvanceResult(song = first track) is returned
5. MusicService.applyAdvance rebuffers → audio + art + title update

Debug log tags: YuriPlayer.Queue, YuriPlayer.AutoPlay, YuriPlayer.Radio
