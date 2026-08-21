package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio backend only. Queue / radio / now-playing identity live in the host
 * ([PlayerSession]). Same contract on Android, desktop, and later iOS.
 */
interface PlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    val currentUri: StateFlow<String?>

    fun load(
        current: PlaybackMedia,
        successor: PlaybackMedia? = null,
        startPositionMs: Long = 0L
    )

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)

    fun getPositionMs(): Long
    fun getDurationMs(): Long

    fun setVolume(percent: Int) {}
    fun getVolume(): Int = 100

    fun setNext(item: PlaybackMedia?) {}
    fun hasPreparedNext(): Boolean = false
    fun playPreparedNext(): Boolean = false
    fun warmupNext() {}

    fun release()

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(playing: Boolean) {}
        fun onEnded() {}
        fun onAutoAdvanced() {}
        fun onError(message: String, recoverable: Boolean) {}
    }
}

data class PlaybackMedia(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val isNetwork: Boolean = false,
    val live: Boolean = false
)

fun Track.toPlaybackMedia(): PlaybackMedia = PlaybackMedia(
    mediaId = id,
    uri = uri,
    title = displayTitle,
    artist = displayArtist,
    album = displayAlbum,
    artworkUri = artworkUri,
    isNetwork = uri.startsWith("http://") || uri.startsWith("https://"),
    live = false
)
