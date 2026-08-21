package capital.yuri.yuriplayer.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Device-aware pacing for long-running library scans so mid/low-end phones
 * (and large remote libraries) don't ANR or thrash GC while indexing.
 *
 * Bias toward **leaving headroom for the main thread** over raw ingest speed.
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
    /** Min interval between any UI-facing StateFlow writes (count / progress). */
    val uiTickMinIntervalMs: Long
    /** Alias used by ExploreSearchService for indexedCount throttling. */
    val countPublishMinIntervalMs: Long get() = uiTickMinIntervalMs
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
                pageSize = 40
                pageYieldMs = 350L
                publishMinIntervalMs = 15_000L
                progressEveryPages = 8
                uiTickMinIntervalMs = 2_500L
                artBatchLimit = 4
                artConcurrency = 1
                artYieldMs = 400L
            }
            Class.MID -> {
                pageSize = 80
                pageYieldMs = 220L
                publishMinIntervalMs = 10_000L
                progressEveryPages = 6
                uiTickMinIntervalMs = 1_800L
                artBatchLimit = 8
                artConcurrency = 1
                artYieldMs = 200L
            }
            Class.HIGH -> {
                pageSize = 120
                pageYieldMs = 120L
                publishMinIntervalMs = 6_000L
                progressEveryPages = 4
                uiTickMinIntervalMs = 1_200L
                artBatchLimit = 16
                artConcurrency = 1
                artYieldMs = 80L
            }
        }
    }

    suspend fun yieldBetweenPages() {
        yield()
        if (pageYieldMs > 0) delay(pageYieldMs)
    }

    suspend fun yieldBetweenArt() {
        yield()
        if (artYieldMs > 0) delay(artYieldMs)
    }
}
