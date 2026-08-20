package capital.yuri.yuriplayer.player.engine

/**
 * @deprecated Prefer a single user-selected engine via [PlaybackEngineFactory].
 * Hybrid auto-routing (VLC local / Media3 network) is removed — one backend
 * plays everything so behaviour matches Settings → Playback engine.
 */
@Deprecated(
    message = "Use PlaybackEngineFactory.create(context, settings.getPlaybackEngineId())",
    replaceWith = ReplaceWith("PlaybackEngineFactory.create(context, id)")
)
typealias HybridPlaybackEngine = Nothing
