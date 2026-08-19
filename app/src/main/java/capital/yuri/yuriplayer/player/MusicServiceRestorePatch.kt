package capital.yuri.yuriplayer.player

/**
 * Startup restore (MusicService):
 * - Restore queue + nowPlaying **before** any Exo prepare so UI binds immediately
 * - **Local**: short yield then prepare
 * - **Remote (Jellyfin/Subsonic)**: defer prepare until the user hits play
 *   ([pendingRemoteRestore]); play()/toggle flush it with auth headers applied
 * - MusicService ExoPlayer uses DefaultHttpDataSource + extractStreamHeaders
 * - WAKE_MODE_NETWORK + lean LoadControl for remote streams
 */
internal object MusicServiceRestorePatch
