package capital.yuri.yuriplayer.data

/**
 * Recurring remote refresh cadence. [OFF] disables that job.
 * Used for profile bits (playlists) and incremental library scans.
 */
enum class SyncInterval(
    val id: String,
    val displayName: String,
    val millis: Long
) {
    OFF("off", "Off", 0L),
    M15("15m", "Every 15 minutes", 15L * 60_000L),
    M30("30m", "Every 30 minutes", 30L * 60_000L),
    H1("1h", "Every hour", 60L * 60_000L),
    H3("3h", "Every 3 hours", 3L * 60L * 60_000L),
    H6("6h", "Every 6 hours", 6L * 60L * 60_000L),
    H12("12h", "Every 12 hours", 12L * 60L * 60_000L),
    D1("1d", "Daily", 24L * 60L * 60_000L),
    W1("1w", "Weekly", 7L * 24L * 60L * 60_000L);

    val isActive: Boolean get() = millis > 0L

    companion object {
        val DEFAULT_PROFILE = H1
        val DEFAULT_PARTIAL = W1

        fun fromId(raw: String?): SyncInterval {
            if (raw.isNullOrBlank()) return OFF
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: OFF
        }
    }
}
