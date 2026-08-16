package capital.yuri.yuriplayer.data

/**
 * Discrete library moments (scans, enrichment). Continuous lists stay on StateFlows.
 */
sealed interface LibraryEvent {
    data class ScanStarted(val atMs: Long = System.currentTimeMillis()) : LibraryEvent

    data class ScanCompleted(
        val songCount: Int,
        val atMs: Long = System.currentTimeMillis()
    ) : LibraryEvent

    data class ScanFailed(
        val message: String,
        val atMs: Long = System.currentTimeMillis()
    ) : LibraryEvent
}
