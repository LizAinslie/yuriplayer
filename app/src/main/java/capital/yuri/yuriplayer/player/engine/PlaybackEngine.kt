package capital.yuri.yuriplayer.player.engine

import android.net.Uri
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic playback backend.
 *
 * Android ships [Media3PlaybackEngine] (ExoPlayer). Desktop / iOS can bind a
 * different implementation; Settings can list [PlaybackEngineDescriptor]s so
 * users pick among several backends on one platform when available.
 *
 * [MusicService] (or a desktop host) owns lifecycle, notifications, and queue
 * policy — this interface is only "make this URI make sound".
 */
interface PlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    val currentUri: StateFlow<Uri?>

    fun setWindow(items: List<PlaybackMedia>, startIndex: Int = 0, startPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(index: Int, positionMs: Long)
    fun seekToNext()
    fun prepare()

    fun getPositionMs(): Long
    fun getDurationMs(): Long
    fun getCurrentIndex(): Int
    fun getMediaCount(): Int
    fun getUriAt(index: Int): Uri?

    fun setPlayWhenReady(value: Boolean)
    fun getPlayWhenReady(): Boolean

    fun release()

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(playing: Boolean) {}
        fun onMediaTransition(reason: TransitionReason) {}
        fun onEnded() {}
        fun onError(message: String, recoverable: Boolean) {}
        fun onPlaybackStateChanged(state: PlaybackState) {}
    }

    enum class TransitionReason { AUTO, SEEK, PLAYLIST, REPEAT, OTHER }
    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }
}

/** One playable item resolved for a backend (URI + optional HTTP headers). */
data class PlaybackMedia(
    val mediaId: String,
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artworkUri: Uri? = null,
    /** Extra request headers (e.g. X-Emby-Token for Jellyfin). */
    val headers: Map<String, String> = emptyMap(),
    val isNetwork: Boolean = false
)

/** Describes a backend the user can choose in Settings. */
data class PlaybackEngineDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    /** android | ios | desktop | … */
    val platforms: Set<String>
)

/** Resolve a [Song] into a [PlaybackMedia] the active engine can play. */
fun Song.toPlaybackMedia(mediaIdSuffix: String? = null): PlaybackMedia {
    val id = if (mediaIdSuffix != null) "${this.id}-$mediaIdSuffix" else this.id.toString()
    val uri = resolvePlayableUri(this)
    val network = isNetworkUri(uri) || isVirtualLibraryPath(path)
    val headers = extractStreamHeaders(this)
    return PlaybackMedia(
        mediaId = id,
        uri = uri,
        title = displayTitle,
        artist = displayArtist,
        album = displayAlbum,
        albumArtist = displayAlbumArtist,
        artworkUri = albumArtUri,
        headers = headers,
        isNetwork = network
    )
}

fun resolvePlayableUri(song: Song): Uri {
    val path = song.path
    // Virtual remote keys (jellyfin:…, subsonic:…) are catalog ids, not files
    if (!path.isNullOrBlank() && !isVirtualLibraryPath(path) && !path.contains("://")) {
        val file = java.io.File(path)
        if (file.exists() && file.canRead()) return Uri.fromFile(file)
    }
    return song.contentUri
}

fun isVirtualLibraryPath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return path.startsWith("jellyfin:") ||
        path.startsWith("subsonic:") ||
        path.startsWith("navidrome:") ||
        path.startsWith("webdav:")
}

fun isNetworkUri(uri: Uri): Boolean {
    val s = uri.scheme?.lowercase() ?: return false
    return s == "http" || s == "https"
}

/** Pull token headers from a stream URI when present (Jellyfin api_key query). */
fun extractStreamHeaders(song: Song): Map<String, String> {
    val uri = song.contentUri
    val apiKey = uri.getQueryParameter("api_key")
        ?: uri.getQueryParameter("ApiKey")
        ?: return emptyMap()
    // Jellyfin accepts either query api_key or X-Emby-Token; send both styles
    // via header for servers that strip query auth on /Audio/.../stream.
    return mapOf(
        "X-Emby-Token" to apiKey,
        "X-MediaBrowser-Token" to apiKey
    )
}

fun urisMatch(a: Uri?, b: Uri?): Boolean {
    if (a == null || b == null) return false
    if (a == b) return true
    // Full string compare — lastPathSegment is "stream" for every Jellyfin track
    return a.toString() == b.toString()
}
