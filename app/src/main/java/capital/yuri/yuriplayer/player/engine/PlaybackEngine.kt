package capital.yuri.yuriplayer.player.engine

import android.net.Uri
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.StreamQuality
import capital.yuri.yuriplayer.http.url
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio backend. Queue, radio, podcasts, and now-playing identity live in the
 * host ([capital.yuri.yuriplayer.player.MusicService] / [capital.yuri.yuriplayer.player.QueueManager]).
 * This type only makes a [PlaybackMedia] URI produce sound — finite track,
 * live radio, or an episode are the same to the engine.
 *
 * Android ships [Media3PlaybackEngine] (ExoPlayer) and [VlcPlaybackEngine].
 */
interface PlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    val currentUri: StateFlow<Uri?>

    /**
     * Replace what the speakers are doing. [items] is **audio**, not a library
     * queue: index 0 is playing (or paused), index 1 is an optional successor
     * buffer for gapless. Live sources should be a single item.
     */
    fun setWindow(items: List<PlaybackMedia>, startIndex: Int = 0, startPositionMs: Long = 0L)

    /** Play [current]; optionally pre-buffer [successor] for an instant handoff. */
    fun load(
        current: PlaybackMedia,
        successor: PlaybackMedia? = null,
        startPositionMs: Long = 0L
    ) {
        val next = successor?.takeUnless { current.live || it.live }
        setWindow(if (next != null) listOf(current, next) else listOf(current), 0, startPositionMs)
        setNext(next)
    }

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

    /** Current source is an endless stream (live radio / ICY). */
    fun isLive(): Boolean = false

    /**
     * Pre-buffer [item] as the upcoming audio without touching what's playing.
     * No-op for live sources. Pass null to drop a previously prepared next.
     */
    fun setNext(item: PlaybackMedia?) {}

    /** True when a next item is loaded/buffered and [playPreparedNext] will start it immediately. */
    fun hasPreparedNext(): Boolean = false

    /** mediaId of the prepared successor, or null. */
    fun preparedNextId(): String? = null

    /**
     * Switch to the pre-buffered next item with no reload.
     * @return false if nothing was prepared (caller should load a new source).
     */
    fun playPreparedNext(): Boolean = false

    /**
     * Start filling the next item's buffers now (near end of a finite track).
     * No-op for live / if nothing is queued.
     */
    fun warmupNext() {}

    fun release()

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(playing: Boolean) {}
        fun onMediaTransition(reason: TransitionReason) {}
        /** Current finite source reached EOF and there was no successor buffer. */
        fun onEnded() {}
        /**
         * Engine already started the pre-buffered successor (gapless swap).
         * Host should sync its own state — do **not** reload current audio.
         */
        fun onAutoAdvanced() {}
        fun onError(message: String, recoverable: Boolean) {}
        fun onPlaybackStateChanged(state: PlaybackState) {}
    }

    enum class TransitionReason { AUTO, SEEK, PLAYLIST, REPEAT, OTHER }
    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }
}

/**
 * One audio source. Metadata is display-only (session / logs); the engine
 * keys on [uri] + [mediaId]. [live] means infinite — no duration, no EOF skip.
 */
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
    val isNetwork: Boolean = false,
    val live: Boolean = false
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
    val live = isLiveAudio()
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
        if (live) append("-live")
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
        isNetwork = network,
        live = live
    )
}

/** Icecast / HLS radio / explicit live query. Finite podcasts and tracks are not live. */
fun Song.isLiveAudio(): Boolean {
    val p = path.orEmpty()
    if (p.startsWith("live:", true) ||
        p.startsWith("icecast:", true) ||
        p.startsWith("shoutcast:", true)
    ) {
        return true
    }
    return contentUri.getBooleanQueryParameter("live", false)
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
    val authKey = apiKey
    val built = url("$scheme://$host") {
        path("Audio", id, "stream")
        param("api_key", authKey)
        param("_id", id)
        if (quality.bitRate == null) {
            param("static", true)
        } else {
            val bps = quality.bitRate
            param("audioCodec", "aac")
            param("container", "mp3")
            param("audioBitRate", bps)
            param("maxStreamingBitrate", bps)
        }
    }
    return Uri.parse(built)
}

/** Subsonic/OpenSubsonic original file vs transcode. */
fun subsonicPlayableUri(
    uri: Uri,
    quality: StreamQuality = StreamQuality.active
): Uri {
    val path = uri.path.orEmpty()
    if (!path.contains("stream", ignoreCase = true) &&
        !path.contains("download", ignoreCase = true)
    ) {
        return uri
    }
    val b = uri.buildUpon().clearQuery()
    val original = quality.bitRate == null
    if (original) {
        // `download` is the original file on Subsonic/OpenSubsonic/Navidrome
        // and is not subject to per-player transcode profiles the way `stream` is.
        uri.encodedPath?.let { encoded ->
            if (encoded.contains("stream", ignoreCase = true)) {
                b.encodedPath(
                    encoded.replace("stream.view", "download.view")
                        .replace("/stream", "/download")
                )
            }
        }
    } else if (uri.encodedPath?.contains("download", ignoreCase = true) == true) {
        uri.encodedPath?.let { encoded ->
            b.encodedPath(
                encoded.replace("download.view", "stream.view")
                    .replace("/download", "/stream")
            )
        }
    }
    for (name in uri.queryParameterNames) {
        if (name.equals("format", true) || name.equals("maxBitRate", true)) continue
        uri.getQueryParameters(name).forEach { value ->
            b.appendQueryParameter(name, value)
        }
    }
    if (original) {
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
