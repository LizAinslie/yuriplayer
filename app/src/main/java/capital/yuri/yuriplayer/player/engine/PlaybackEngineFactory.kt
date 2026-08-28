package capital.yuri.yuriplayer.player.engine

import android.content.Context
import capital.yuri.yuriplayer.core.log.yuriLog

/**
 * Which [PlaybackEngine] implementation plays **all** audio (local + remote).
 * Chosen in Settings; applied when the service (re)creates the engine.
 */
enum class PlaybackEngineId(val id: String) {
    MEDIA3("media3"),
    VLC("vlc"),
    /** Reserved — full FFmpeg AudioTrack backend not wired yet. */
    FFMPEG("ffmpeg");

    companion object {
        fun fromId(raw: String?): PlaybackEngineId {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == key } ?: VLC
        }
    }
}

object PlaybackEngineCatalog {
    val available: List<PlaybackEngineDescriptor> = listOf(
        VlcPlaybackEngine.DESCRIPTOR,
        Media3PlaybackEngine.DESCRIPTOR,
        PlaybackEngineDescriptor(
            id = PlaybackEngineId.FFMPEG.id,
            displayName = "FFmpeg (planned)",
            description = "Decode via bundled FFmpeg → AudioTrack. Not available yet.",
            platforms = setOf("android")
        )
    )

    fun descriptor(id: PlaybackEngineId): PlaybackEngineDescriptor =
        available.firstOrNull { it.id == id.id }
            ?: VlcPlaybackEngine.DESCRIPTOR
}

/**
 * Builds the single active [PlaybackEngine] for the process.
 * One selection plays everything — no per-URI hybrid routing.
 */
object PlaybackEngineFactory {
    private val log = yuriLog("EngineFactory")
    fun create(context: Context, id: PlaybackEngineId): PlaybackEngine {
        val app = context.applicationContext
        return when (id) {
            PlaybackEngineId.MEDIA3 -> {
                log.i { "create Media3PlaybackEngine" }
                Media3PlaybackEngine(app)
            }
            PlaybackEngineId.VLC -> {
                log.i { "create VlcPlaybackEngine" }
                VlcPlaybackEngine(app)
            }
            PlaybackEngineId.FFMPEG -> {
                // Not implemented — fall back so playback still works.
                log.w { "FFmpeg engine not ready; falling back to VLC" }
                VlcPlaybackEngine(app)
            }
        }
    }
}
