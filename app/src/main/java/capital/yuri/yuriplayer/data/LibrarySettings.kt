package capital.yuri.yuriplayer.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * User-configurable library roots.
 * Defaults cover Music/[Album] and the future Music/library/[Album] layout.
 * Paths are stored relative to external storage root where possible.
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

    companion object {
        private const val PREFS = "library_settings"
        private const val KEY_ROOTS = "scan_roots"

        val DEFAULT_ROOTS = listOf(
            "Music",
            "Music/library",
            "Download"
        )
    }
}
