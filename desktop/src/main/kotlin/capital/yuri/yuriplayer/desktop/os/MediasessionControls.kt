package capital.yuri.yuriplayer.desktop.os

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.core.os.OsMediaControls
import capital.yuri.yuriplayer.core.player.RepeatMode
import capital.yuri.yuriplayer.data.Song
import dev.toastbits.mediasession.MediaSession
import dev.toastbits.mediasession.MediaSessionLoopMode
import dev.toastbits.mediasession.MediaSessionMetadata
import dev.toastbits.mediasession.MediaSessionPlaybackStatus
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-platform system media session backed by dev.toastbits:mediasession.
 *
 * Linux -> MPRIS (`playerctl`, GNOME/KDE media keys); Windows -> SMTC.
 * The library returns null on macOS, which keeps its own JNA Now Playing impl
 * (see [capital.yuri.yuriplayer.desktop.os.mac.MacNowPlayingControls]).
 */
class MediasessionControls : OsMediaControls {
    private val log = yuriLog("Mediasession")

    private val positionMs = AtomicLong(0L)
    private val lastTrackId = AtomicReference<String?>(null)
    private val lastStatus = AtomicReference<MediaSessionPlaybackStatus?>(null)
    private val lastVolume = AtomicReference<Float?>(null)
    private val lastLoop = AtomicReference<RepeatMode?>(null)
    private val lastShuffle = AtomicReference<Boolean?>(null)

    private val session: MediaSession? = runCatching {
        MediaSession.create { positionMs.get() }
    }.onFailure { t ->
        log.w(t) { "mediasession unavailable: ${t.message}" }
    }.getOrNull()

    override fun attach(callbacks: OsMediaControls.Callbacks) {
        val s = session ?: return
        runCatching {
            s.onRaise = { callbacks.onRaise() }
            s.onQuit = { callbacks.onQuit() }
            s.onNext = { callbacks.onNext() }
            s.onPrevious = { callbacks.onPrevious() }
            s.onPause = { callbacks.onPause() }
            s.onPlayPause = { callbacks.onPlayPause() }
            s.onStop = { callbacks.onStop() }
            s.onPlay = { callbacks.onPlay() }
            // The vendored library converts MPRIS microseconds to ms here.
            s.onSeek = { byMs -> callbacks.onSeek((positionMs.get() + byMs).coerceAtLeast(0)) }
            s.onSetPosition = { toMs -> callbacks.onSeek(toMs.coerceAtLeast(0)) }
            s.onSetLoop = { mode -> callbacks.onLoop(mode.toRepeatMode()) }
            s.onSetShuffle = { enabled -> callbacks.onShuffle(enabled) }
            s.onSetVolume = { v -> callbacks.onVolume(v) }
            s.setIdentity(APP_ID)
            s.setDesktopEntry(APP_ID)
            s.setSupportedUriSchemes(SUPPORTED_SCHEMES)
            s.setSupportedMimeTypes(SUPPORTED_MIME_TYPES)
            s.setEnabled(true)
            log.i { "mediasession enabled as $APP_ID" }
        }.onFailure { t ->
            log.w(t) { "mediasession attach failed: ${t.message}" }
        }
    }

    override fun update(
        track: Song?,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float
    ) {
        val s = session ?: return
        this.positionMs.set(positionMs)

        // mediasession 0.1.1 is rough around the edges (e.g. Float vs Double in
        // setVolume); never let a marshalling bug crash the ticker coroutine.
        runCatching {
            val status = when {
                playing -> MediaSessionPlaybackStatus.PLAYING
                track != null -> MediaSessionPlaybackStatus.PAUSED
                else -> MediaSessionPlaybackStatus.STOPPED
            }
            if (lastStatus.getAndSet(status) != status) {
                s.setPlaybackStatus(status)
            }

            val trackId = track?.songKey
            if (lastTrackId.getAndSet(trackId) != trackId) {
                s.setMetadata(track?.toMediaSessionMetadata(durationMs) ?: MediaSessionMetadata())
            }

            val v = volume.coerceIn(0f, 1f)
            if (lastVolume.getAndSet(v) != v) {
                s.setVolume(v)
            }
        }.onFailure { t ->
            log.w(t) { "mediasession update failed: ${t.message}" }
        }
    }

    override fun setLoop(mode: RepeatMode) {
        val s = session ?: return
        if (lastLoop.getAndSet(mode) == mode) return
        runCatching { s.setLoopMode(mode.toMediaSessionLoopMode()) }
            .onFailure { t -> log.w(t) { "mediasession setLoop failed: ${t.message}" } }
    }

    override fun setShuffle(enabled: Boolean) {
        val s = session ?: return
        if (lastShuffle.getAndSet(enabled) == enabled) return
        runCatching { s.setShuffle(enabled) }
            .onFailure { t -> log.w(t) { "mediasession setShuffle failed: ${t.message}" } }
    }

    override fun release() {
        runCatching { session?.setEnabled(false) }
    }

    companion object {
        const val APP_ID = "yuriplayer"
        private val SUPPORTED_SCHEMES = listOf("file", "http", "https")
        private val SUPPORTED_MIME_TYPES = listOf(
            "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4"
        )
    }
}

private fun Song.toMediaSessionMetadata(durationMs: Long): MediaSessionMetadata =
    MediaSessionMetadata(
        track_id = mprisTrackId(songKey),
        title = displayTitle,
        artist = displayArtist,
        album = displayAlbum,
        album_artists = listOf(displayArtist),
        length_ms = durationMs.takeIf { it > 0 },
        art_url = albumArtUri?.takeIf { it.startsWith("file:") || it.startsWith("http") }
    )

/**
 * MPRIS `mpris:trackid` must be a valid DBus object path: starts with `/` and
 * every non-empty segment matches `[A-Za-z0-9_]+`. Any other character is
 * collapsed to `_`.
 */
private fun mprisTrackId(id: String): String {
    val safe = id
        .map { c -> if (c.isLetterOrDigit() || c == '_') c else '_' }
        .joinToString("")
        .takeLast(48)
        .ifBlank { "track" }
    return "/org/mpris/MediaPlayer2/Track/$safe"
}

private fun RepeatMode.toMediaSessionLoopMode(): MediaSessionLoopMode = when (this) {
    RepeatMode.OFF -> MediaSessionLoopMode.NONE
    RepeatMode.COLD -> MediaSessionLoopMode.ALL
    RepeatMode.ONE -> MediaSessionLoopMode.ONE
}

private fun MediaSessionLoopMode.toRepeatMode(): RepeatMode = when (this) {
    MediaSessionLoopMode.NONE -> RepeatMode.OFF
    MediaSessionLoopMode.ONE -> RepeatMode.ONE
    MediaSessionLoopMode.ALL -> RepeatMode.COLD
}
