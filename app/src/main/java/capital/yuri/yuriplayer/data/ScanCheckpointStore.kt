package capital.yuri.yuriplayer.data

import android.content.Context

enum class SourceScanStatus {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    DONE
}

data class SourceScanCheckpoint(
    val sourceInstanceId: Long,
    val sourceName: String,
    val status: SourceScanStatus,
    /** Next server startIndex to request (Jellyfin paging cursor). */
    val startIndex: Int = 0,
    val delivered: Int = 0,
    val totalHint: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Durable per-source scan cursor so leaving Explore / killing the UI never
 * restarts a 40k-track index from zero. The FGS owns the work; this store owns
 * "where we were".
 */
class ScanCheckpointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(sourceInstanceId: Long): SourceScanCheckpoint? {
        val prefix = keyPrefix(sourceInstanceId)
        if (!prefs.contains("${prefix}status")) return null
        val status = runCatching {
            SourceScanStatus.valueOf(prefs.getString("${prefix}status", SourceScanStatus.IDLE.name)!!)
        }.getOrDefault(SourceScanStatus.IDLE)
        return SourceScanCheckpoint(
            sourceInstanceId = sourceInstanceId,
            sourceName = prefs.getString("${prefix}name", "Source") ?: "Source",
            status = status,
            startIndex = prefs.getInt("${prefix}start", 0),
            delivered = prefs.getInt("${prefix}delivered", 0),
            totalHint = prefs.getInt("${prefix}total", -1).takeIf { it >= 0 },
            updatedAtMs = prefs.getLong("${prefix}updated", 0L)
        )
    }

    fun all(): List<SourceScanCheckpoint> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        return ids.mapNotNull { id -> id.toLongOrNull()?.let { get(it) } }
            .sortedBy { it.sourceName.lowercase() }
    }

    fun save(cp: SourceScanCheckpoint) {
        val prefix = keyPrefix(cp.sourceInstanceId)
        val ids = prefs.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        ids += cp.sourceInstanceId.toString()
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .putString("${prefix}status", cp.status.name)
            .putString("${prefix}name", cp.sourceName)
            .putInt("${prefix}start", cp.startIndex.coerceAtLeast(0))
            .putInt("${prefix}delivered", cp.delivered.coerceAtLeast(0))
            .putInt("${prefix}total", cp.totalHint ?: -1)
            .putLong("${prefix}updated", cp.updatedAtMs)
            .apply()
    }

    fun markRunning(sourceInstanceId: Long, sourceName: String, startIndex: Int, delivered: Int, totalHint: Int?) {
        save(
            SourceScanCheckpoint(
                sourceInstanceId = sourceInstanceId,
                sourceName = sourceName,
                status = SourceScanStatus.RUNNING,
                startIndex = startIndex,
                delivered = delivered,
                totalHint = totalHint
            )
        )
    }

    fun markPaused(sourceInstanceId: Long, sourceName: String, startIndex: Int, delivered: Int, totalHint: Int?) {
        save(
            SourceScanCheckpoint(
                sourceInstanceId = sourceInstanceId,
                sourceName = sourceName,
                status = SourceScanStatus.PAUSED,
                startIndex = startIndex,
                delivered = delivered,
                totalHint = totalHint
            )
        )
    }

    fun markStopped(sourceInstanceId: Long, sourceName: String, startIndex: Int, delivered: Int, totalHint: Int?) {
        save(
            SourceScanCheckpoint(
                sourceInstanceId = sourceInstanceId,
                sourceName = sourceName,
                status = SourceScanStatus.STOPPED,
                startIndex = startIndex,
                delivered = delivered,
                totalHint = totalHint
            )
        )
    }

    fun markDone(sourceInstanceId: Long, sourceName: String, delivered: Int, totalHint: Int?) {
        save(
            SourceScanCheckpoint(
                sourceInstanceId = sourceInstanceId,
                sourceName = sourceName,
                status = SourceScanStatus.DONE,
                startIndex = delivered,
                delivered = delivered,
                totalHint = totalHint
            )
        )
    }

    fun clear(sourceInstanceId: Long) {
        val prefix = keyPrefix(sourceInstanceId)
        val ids = prefs.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        ids.remove(sourceInstanceId.toString())
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .remove("${prefix}status")
            .remove("${prefix}name")
            .remove("${prefix}start")
            .remove("${prefix}delivered")
            .remove("${prefix}total")
            .remove("${prefix}updated")
            .apply()
    }

    private fun keyPrefix(id: Long) = "src_${id}_"

    companion object {
        private const val PREFS = "scan_checkpoints"
        private const val KEY_IDS = "ids"
    }
}
