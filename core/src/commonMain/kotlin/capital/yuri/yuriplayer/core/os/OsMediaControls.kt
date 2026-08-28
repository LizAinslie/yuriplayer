package capital.yuri.yuriplayer.core.os

import capital.yuri.yuriplayer.core.player.RepeatMode
import capital.yuri.yuriplayer.data.Song

/**
 * System playback integration: MPRIS (`playerctl`) on Linux, Now Playing on
 * macOS, System Media Transport Controls on Windows.
 */
interface OsMediaControls {
    fun attach(callbacks: Callbacks)

    fun update(
        track: Song?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float = 1f
    )

    /** Push repeat mode to the system media session (app -> OS). */
    fun setLoop(mode: RepeatMode) {}

    /** Push shuffle state to the system media session (app -> OS). */
    fun setShuffle(enabled: Boolean) {}

    fun release()

    interface Callbacks {
        fun onPlay()
        fun onPause()
        fun onPlayPause()
        fun onStop()
        fun onNext()
        fun onPrevious()
        fun onSeek(positionMs: Long)
        fun onVolume(value: Float) {}
        fun onLoop(mode: RepeatMode) {}
        fun onShuffle(enabled: Boolean) {}
        fun onRaise() {}
        fun onQuit() {}
    }
}

object NoOpMediaControls : OsMediaControls {
    override fun attach(callbacks: OsMediaControls.Callbacks) {}
    override fun update(
        track: Song?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float
    ) {}
    override fun release() {}
}
