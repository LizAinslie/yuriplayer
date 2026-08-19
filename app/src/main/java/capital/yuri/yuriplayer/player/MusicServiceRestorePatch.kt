package capital.yuri.yuriplayer.player

/**
 * Startup restore notes (implemented in MusicService):
 * - restore queue + nowPlaying before Exo prepare
 * - yield/delay before network rebuffer (Jellyfin)
 * - songUri/urisEqual/toMediaItem via MusicServicePlaybackHooks
 * - WAKE_MODE_NETWORK + leaner LoadControl for remote streams
 */
internal object MusicServiceRestorePatch
