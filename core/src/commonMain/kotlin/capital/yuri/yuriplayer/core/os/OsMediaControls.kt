package capital.yuri.yuriplayer.core.os

import capital.yuri.yuriplayer.core.library.Track

/**
 * System playback integration: MPRIS (`playerctl`) on Linux, Now Playing on
 * macOS, System Media Transport Controls on Windows.
 */
interface OsMediaControls {
    fun attach(callbacks: Callbacks)
    fun update(
        track: Track?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float = 1f
    )
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
        fun onRaise() {}
        fun onQuit() {}
    }
}

object NoOpMediaControls : OsMediaControls {
    override fun attach(callbacks: OsMediaControls.Callbacks) {}
    override fun update(
        track: Track?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float
    ) {}
    override fun release() {}
}
