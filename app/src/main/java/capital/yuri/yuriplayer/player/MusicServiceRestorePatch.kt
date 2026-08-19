package capital.yuri.yuriplayer.player

/**
 * Startup restore (MusicService):
 * - Restore queue + nowPlaying **before** any Exo prepare so UI binds immediately
 * - **Local**: short yield then prepare
 * - **Remote (Jellyfin/Subsonic)**: defer prepare until the user hits play
 *   ([pendingRemoteRestore]); play()/toggle flush it with auth headers applied
 * - ExoPlayer uses [androidx.media3.datasource.DefaultDataSource] wrapping
 *   DefaultHttpDataSource so content:// / file:// and HTTP streams both work;
 *   extractStreamHeaders still sets tokens on the HTTP factory
 * - WAKE_MODE_NETWORK + lean LoadControl for remote streams
 */
internal object MusicServiceRestorePatch
