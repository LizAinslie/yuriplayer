package capital.yuri.yuriplayer.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Device-aware pacing for long-running library scans so mid/low-end phones
 * (and large remote libraries) don't ANR or thrash GC while indexing.
 */
class ScanBudget(context: Context) {

    enum class Class { LOW, MID, HIGH }

    val deviceClass: Class
    val pageSize: Int
    /** Sleep between network pages so UI / GC can breathe. */
    val pageYieldMs: Long
    /** Min interval between full remoteOfferings StateFlow publishes. */
    val publishMinIntervalMs: Long
    /** How many album-art downloads to attempt in one post-scan pass. */
    val artBatchLimit: Int
    val artConcurrency: Int
    val artYieldMs: Long

    init {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mem.totalMem / (1024L * 1024L)
        val lowRam = if (Build.VERSION.SDK_INT >= 19) am.isLowRamDevice else false
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        deviceClass = when {
            lowRam || totalMb in 1 until 3_000 || cores <= 4 -> Class.LOW
            totalMb < 6_000 || cores <= 6 -> Class.MID
            else -> Class.HIGH
        }

        when (deviceClass) {
            Class.LOW -> {
                pageSize = 80
                pageYieldMs = 120L
                publishMinIntervalMs = 4_000L
                artBatchLimit = 8
                artConcurrency = 1
                artYieldMs = 250L
            }
            Class.MID -> {
                pageSize = 150
                pageYieldMs = 60L
                publishMinIntervalMs = 2_500L
                artBatchLimit = 16
                artConcurrency = 1
                artYieldMs = 120L
            }
            Class.HIGH -> {
                pageSize = 250
                pageYieldMs = 25L
                publishMinIntervalMs = 1_500L
                artBatchLimit = 32
                artConcurrency = 2
                artYieldMs = 40L
            }
        }
    }

    suspend fun yieldBetweenPages() {
        if (pageYieldMs > 0) delay(pageYieldMs)
    }

    suspend fun yieldBetweenArt() {
        if (artYieldMs > 0) delay(artYieldMs)
    }
}
