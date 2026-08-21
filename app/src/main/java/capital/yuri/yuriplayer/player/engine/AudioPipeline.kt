package capital.yuri.yuriplayer.player.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What we asked the server for vs what actually arrived.
 * Filter logcat: `adb logcat YuriAudio:I *:S`
 */
object AudioPipeline {
    const val TAG = "YuriAudio"

    data class Snapshot(
        val title: String,
        val engine: String,
        val quality: String,
        val requested: String,
        val uriHint: String,
        val httpType: String? = null,
        val httpBytes: Long? = null,
        val codec: String? = null,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
        val bitrateKbps: Int? = null
    ) {
        val summary: String
            get() = buildString {
                append(quality)
                append(" · ")
                append(requested)
                httpType?.let { append(" · ").append(it) }
                codec?.let { append(" · ").append(it) }
                if (sampleRateHz != null) append(" · ${sampleRateHz} Hz")
                if (channels != null) append(" · ${channels}ch")
                if (bitrateKbps != null && bitrateKbps > 0) append(" · ${bitrateKbps} kbps")
                httpBytes?.takeIf { it > 0 }?.let { append(" · ${it / 1024} KB") }
            }
    }

    private val _last = MutableStateFlow<Snapshot?>(null)
    val last: StateFlow<Snapshot?> = _last.asStateFlow()

    fun i(msg: String) = Log.i(TAG, msg)

    fun notePlay(
        title: String,
        engine: String,
        quality: String,
        uri: String
    ) {
        val requested = requestedFromUri(uri)
        val hint = redact(uri)
        i("play '$title' engine=$engine quality=$quality $requested $hint")
        _last.value = Snapshot(
            title = title,
            engine = engine,
            quality = quality,
            requested = requested,
            uriHint = hint
        )
    }

    fun noteHttp(contentType: String?, bytes: Long?, title: String? = null) {
        i("http type=$contentType bytes=$bytes ${title.orEmpty()}")
        val cur = _last.value ?: return
        _last.value = cur.copy(
            httpType = contentType ?: cur.httpType,
            httpBytes = bytes ?: cur.httpBytes
        )
    }

    fun noteDecoded(
        codec: String?,
        sampleRateHz: Int?,
        channels: Int?,
        bitrateBps: Int?
    ) {
        val kbps = bitrateBps?.takeIf { it > 0 }?.div(1000)
        i("decoded codec=$codec ${sampleRateHz ?: "?"}Hz ch=${channels ?: "?"} bitrate=${kbps ?: "?"}kbps")
        val cur = _last.value ?: return
        _last.value = cur.copy(
            codec = codec ?: cur.codec,
            sampleRateHz = sampleRateHz ?: cur.sampleRateHz,
            channels = channels ?: cur.channels,
            bitrateKbps = kbps ?: cur.bitrateKbps
        )
    }

    fun requestedFromUri(uri: String): String {
        val lower = uri.lowercase()
        return when {
            lower.contains("/download") -> "original-file (download)"
            lower.contains("format=raw") -> "original (format=raw)"
            lower.contains("format=opus") -> "transcode opus"
            lower.contains("format=mp3") -> "transcode mp3"
            lower.contains("static=true") -> "original (jellyfin static)"
            else -> "stream"
        }
    }

    fun redact(uri: String): String =
        uri.replace(Regex("([?&](t|s|p|api_key|ApiKey)=)[^&]+"), "$1…")
            .let { if (it.length > 180) it.take(180) + "…" else it }
}
