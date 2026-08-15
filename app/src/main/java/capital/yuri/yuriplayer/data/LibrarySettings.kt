package capital.yuri.yuriplayer.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * User-configurable library roots + metadata preferences.
 *
 * Online year/art lookup is **manual by default** (album/artist "Fetch additional
 * metadata"). Users can enable automatic background enrichment in Settings.
 *
 * Note: [android.permission.INTERNET] is a normal permission granted at install;
 * there is no system runtime dialog for it.
 */
class LibrarySettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getScanRoots(): List<File> {
        val stored = prefs.getStringSet(KEY_ROOTS, null)
        val relative = if (stored.isNullOrEmpty()) DEFAULT_ROOTS else stored.toList()

        val external = Environment.getExternalStorageDirectory()
        return relative.map { rel ->
            if (rel.startsWith("/")) File(rel) else File(external, rel)
        }.distinct()
    }

    fun setScanRoots(relativePaths: Collection<String>) {
        prefs.edit().putStringSet(KEY_ROOTS, relativePaths.toSet()).apply()
    }

    fun addScanRoot(relativePath: String) {
        val current = prefs.getStringSet(KEY_ROOTS, DEFAULT_ROOTS.toSet())?.toMutableSet() ?: mutableSetOf()
        current += relativePath.trim().trimStart('/')
        prefs.edit().putStringSet(KEY_ROOTS, current).apply()
    }

    /**
     * When true, missing years/covers are fetched in the background after scans
     * and when opening album/artist pages. Default **false** — use the manual
     * "Fetch additional metadata" action instead.
     */
    fun isAutomaticMetadataEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_METADATA, false)

    fun setAutomaticMetadataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_METADATA, enabled).apply()
    }

    // Keep old key readable once so existing opt-ins still map to automatic.
    fun migrateLegacyNetworkConsentIfNeeded() {
        if (prefs.contains(KEY_NETWORK_META) && !prefs.contains(KEY_AUTO_METADATA)) {
            val legacy = prefs.getBoolean(KEY_NETWORK_META, false)
            prefs.edit()
                .putBoolean(KEY_AUTO_METADATA, legacy)
                .remove(KEY_NETWORK_META)
                .apply()
        }
    }

    companion object {
        private const val PREFS = "library_settings"
        private const val KEY_ROOTS = "scan_roots"
        private const val KEY_AUTO_METADATA = "automatic_metadata_enabled"
        private const val KEY_NETWORK_META = "network_metadata_enabled" // legacy

        val DEFAULT_ROOTS = listOf(
            "Music",
            "Music/library",
            "Download"
        )
    }
}
