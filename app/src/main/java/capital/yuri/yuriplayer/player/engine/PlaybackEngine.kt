package capital.yuri.yuriplayer.player.engine

import android.net.Uri
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StreamQuality
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

    /** True while the backend is filling buffers (HTTP underrun, demux, …). */
    fun isBuffering(): Boolean = false

    /**
     * Pre-buffer [item] as the upcoming track without touching what's playing.
     * Pass null to drop a previously prepared next.
     */
    fun setNext(item: PlaybackMedia?) {}

    /** True when a next item is loaded/buffered and [playPreparedNext] will start it immediately. */
    fun hasPreparedNext(): Boolean = false

    /**
     * Switch to the pre-buffered next item with no reload.
     * @return false if nothing was prepared (caller should load a new window).
     */
    fun playPreparedNext(): Boolean = false

    /**
     * Start filling the next item's buffers now (call when the current track
     * is near the end). No-op if nothing is queued.
     */
    fun warmupNext() {}

    fun release()

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(playing: Boolean) {}
        fun onMediaTransition(reason: TransitionReason) {}
        fun onEnded() {}
        /**
         * Engine already started the pre-buffered next item (gapless / dual-player swap).
         * Host should advance queue state and call [setNext] — do **not** reload current.
         */
        fun onAutoAdvanced() {}
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
fun Song.toPlaybackMedia(
    mediaIdSuffix: String? = null,
    quality: StreamQuality = StreamQuality.active
): PlaybackMedia {
    val uri = resolvePlayableUri(this, quality)
    val network = isNetworkUri(uri) || isVirtualLibraryPath(path)
    val headers = extractStreamHeaders(this)
    val id = buildString {
        append(this@toPlaybackMedia.id)
        if (mediaIdSuffix != null) {
            append('-')
            append(mediaIdSuffix)
        }
        if (network) {
            append("-q")
            append(quality.id)
        }
    }
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

fun resolvePlayableUri(
    song: Song,
    quality: StreamQuality = StreamQuality.active
): Uri {
    val path = song.path
    // Virtual remote keys (jellyfin:…, subsonic:…) are catalog ids, not files
    if (!path.isNullOrBlank() && !isVirtualLibraryPath(path) && !path.contains("://")) {
        val file = java.io.File(path)
        if (file.exists() && file.canRead()) return Uri.fromFile(file)
    }
    if (!path.isNullOrBlank() && path.startsWith("jellyfin:")) {
        return jellyfinPlayableUri(song.contentUri, path.removePrefix("jellyfin:"), quality)
    }
    if (!path.isNullOrBlank() &&
        (path.startsWith("subsonic:") || path.startsWith("navidrome:"))
    ) {
        return subsonicPlayableUri(song.contentUri, quality)
    }
    return song.contentUri
}

/**
 * Rewrite Jellyfin catalog URIs at play time.
 * Original → `/stream?static=true`. Other qualities request an AAC/MP3 transcode
 * at the chosen bitrate so prefetch stays small on cellular.
 */
fun jellyfinPlayableUri(
    uri: Uri,
    itemId: String,
    quality: StreamQuality = StreamQuality.active
): Uri {
    val scheme = uri.scheme?.lowercase() ?: return uri
    if (scheme != "http" && scheme != "https") return uri
    val host = uri.encodedAuthority ?: return uri
    val apiKey = uri.getQueryParameter("api_key")
        ?: uri.getQueryParameter("ApiKey")
        ?: return uri
    val id = itemId.ifBlank {
        uri.getQueryParameter("_id")
            ?: uri.pathSegments.let { segs ->
                val i = segs.indexOfFirst { it.equals("Audio", true) }
                segs.getOrNull(i + 1)
            }
    } ?: return uri
    val auth = "api_key=${Uri.encode(apiKey)}&_id=$id"
    return if (quality.bitRate == null) {
        Uri.parse("$scheme://$host/Audio/$id/stream?static=true&$auth")
    } else {
        val bps = quality.bitRate
        Uri.parse(
            "$scheme://$host/Audio/$id/stream?$auth" +
                "&audioCodec=aac&container=mp3" +
                "&audioBitRate=$bps&maxStreamingBitrate=$bps"
        )
    }
}

/** Subsonic/OpenSubsonic: `format=raw` for original, else mp3 + maxBitRate. */
fun subsonicPlayableUri(
    uri: Uri,
    quality: StreamQuality = StreamQuality.active
): Uri {
    val path = uri.path.orEmpty()
    if (!path.contains("stream", ignoreCase = true)) return uri
    val b = uri.buildUpon().clearQuery()
    for (name in uri.queryParameterNames) {
        if (name.equals("format", true) || name.equals("maxBitRate", true)) continue
        uri.getQueryParameters(name).forEach { value ->
            b.appendQueryParameter(name, value)
        }
    }
    if (quality.bitRate == null) {
        b.appendQueryParameter("format", "raw")
    } else {
        b.appendQueryParameter("format", "mp3")
        b.appendQueryParameter("maxBitRate", quality.kbps.toString())
    }
    return b.build()
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
