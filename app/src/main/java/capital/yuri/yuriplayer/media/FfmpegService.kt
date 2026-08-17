package capital.yuri.yuriplayer.media

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin Koin-friendly wrapper around FFmpeg Kit for GIF/image crops and later
 * audio/video jobs. All work is off the main thread.
 */
class FfmpegService {

    /**
     * Crop [input] to a square (or [outW]x[outH]) centered region and write [output].
     * Works for still images and animated GIFs (re-encodes GIF frames).
     */
    suspend fun cropCenter(
        input: File,
        output: File,
        outW: Int = 512,
        outH: Int = 512
    ): Boolean = withContext(Dispatchers.IO) {
        if (!input.isFile) return@withContext false
        output.parentFile?.mkdirs()
        // scale to cover, then crop center
        val filter =
            "scale=$outW:$outH:force_original_aspect_ratio=increase," +
                "crop=$outW:$outH"
        val cmd = "-y -i \"${input.absolutePath}\" -vf \"$filter\" \"${output.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode)
        if (!ok) {
            Log.w(TAG, "crop failed: ${session.failStackTrace ?: session.output}")
        }
        ok && output.isFile && output.length() > 0L
    }

    suspend fun execute(args: String): Boolean = withContext(Dispatchers.IO) {
        val session = FFmpegKit.execute(args)
        ReturnCode.isSuccess(session.returnCode)
    }

    companion object {
        private const val TAG = "FfmpegService"
    }
}
