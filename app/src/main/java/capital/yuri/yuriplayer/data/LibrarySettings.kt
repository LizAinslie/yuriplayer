package capital.yuri.yuriplayer.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * User-configurable library roots + network metadata preference.
 *
 * Note: [android.permission.INTERNET] is a normal permission — Android grants it
 * at install and never shows a system dialog. We still ask the user once before
 * calling MusicBrainz / Cover Art Archive so enrichment is an explicit opt-in.
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

    /** null = never asked, true/false = user choice. */
    fun networkMetadataConsent(): Boolean? {
        if (!prefs.contains(KEY_NETWORK_META)) return null
        return prefs.getBoolean(KEY_NETWORK_META, false)
    }

    fun setNetworkMetadataConsent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NETWORK_META, enabled).apply()
    }

    fun isNetworkMetadataEnabled(): Boolean =
        networkMetadataConsent() == true

    companion object {
        private const val PREFS = "library_settings"
        private const val KEY_ROOTS = "scan_roots"
        private const val KEY_NETWORK_META = "network_metadata_enabled"

        val DEFAULT_ROOTS = listOf(
            "Music",
            "Music/library",
            "Download"
        )
    }
}
