Auto-play recommended is wired via MusicServiceAutoPlay + ArtistRadio.

MusicService must call:
1. autoPlay.noteSource(source) in playSource()
2. on AdvanceResult.finished / empty queue end:
   val seed = queueManager.peekPrevious()
   val pick = autoPlay.maybePick(seed, queueManager.coldSource(), snap.repeatMode)
   if (pick != null) playSource(pick.album.songs, 0, pick.source)

Inject LibraryIndex + LibrarySettings into MusicService and construct MusicServiceAutoPlay.
