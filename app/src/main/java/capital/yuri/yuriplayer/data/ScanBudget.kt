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
    /** Update notification / progress text every N pages (keeps main thread free). */
    val progressEveryPages: Int
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
                pageYieldMs = 150L
                publishMinIntervalMs = 8_000L
                progressEveryPages = 4
                artBatchLimit = 8
                artConcurrency = 1
                artYieldMs = 250L
            }
            Class.MID -> {
                pageSize = 150
                pageYieldMs = 80L
                publishMinIntervalMs = 5_000L
                progressEveryPages = 3
                artBatchLimit = 16
                artConcurrency = 1
                artYieldMs = 120L
            }
            Class.HIGH -> {
                pageSize = 250
                pageYieldMs = 30L
                publishMinIntervalMs = 2_500L
                progressEveryPages = 2
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
