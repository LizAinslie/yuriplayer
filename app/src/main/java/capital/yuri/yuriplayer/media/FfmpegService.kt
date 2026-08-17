package capital.yuri.yuriplayer.media

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a project-built FFmpeg binary (NDK cross-compile under native/ffmpeg).
 * Binary is packaged in assets/ffmpeg/<abi>/ffmpeg and extracted once to filesDir.
 *
 * If the binary is missing (NDK build skipped), crop falls back to false and the
 * UI uses Bitmap still-image crop instead.
 */
class FfmpegService(private val context: Context) {

    private val installLock = Mutex()
    @Volatile private var binary: File? = null

    /**
     * Crop [input] to [outW]x[outH] (scale-to-cover + center crop).
     * Works for stills and animated GIFs when the native binary is present.
     */
    suspend fun cropCenter(
        input: File,
        output: File,
        outW: Int = 512,
        outH: Int = 512
    ): Boolean = withContext(Dispatchers.IO) {
        if (!input.isFile) return@withContext false
        val ff = ensureBinary() ?: return@withContext false
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val filter =
            "scale=$outW:$outH:force_original_aspect_ratio=increase," +
                "crop=$outW:$outH"
        val args = listOf(
            ff.absolutePath,
            "-y",
            "-i", input.absolutePath,
            "-vf", filter,
            output.absolutePath
        )
        val ok = runProcess(args)
        ok && output.isFile && output.length() > 0L
    }

    suspend fun execute(args: List<String>): Boolean = withContext(Dispatchers.IO) {
        val ff = ensureBinary() ?: return@withContext false
        runProcess(listOf(ff.absolutePath) + args)
    }

    private suspend fun ensureBinary(): File? = installLock.withLock {
        binary?.takeIf { it.canExecute() }?.let { return it }

        val abi = preferredAbi()
        val dest = File(context.filesDir, "bin/ffmpeg-$abi").also {
            it.parentFile?.mkdirs()
        }

        // Re-extract if missing or zero-length
        if (!dest.isFile || dest.length() == 0L) {
            val assetPath = "ffmpeg/$abi/ffmpeg"
            val copied = runCatching {
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            if (!copied) {
                Log.w(TAG, "No FFmpeg asset for abi=$abi ($assetPath). Build native/ffmpeg first.")
                return null
            }
        }

        dest.setReadable(true, true)
        dest.setExecutable(true, true)
        if (!dest.canExecute()) {
            Log.w(TAG, "FFmpeg binary not executable: $dest")
            return null
        }
        binary = dest
        dest
    }

    private fun preferredAbi(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a", "armeabi-v7a", "x86_64", "x86" -> return abi
            }
        }
        return "arm64-v8a"
    }

    private fun runProcess(args: List<String>): Boolean {
        return try {
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(context.cacheDir)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            if (code != 0) {
                Log.w(TAG, "ffmpeg exit=$code\n$log")
            }
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "ffmpeg exec failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "FfmpegService"
    }
}
