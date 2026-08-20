package capital.yuri.yuriplayer.data.organize

import android.content.Context
import org.json.JSONObject

/**
 * Persists [OrganizeLayout] keyed by root id (SAF tree URI or future mount id).
 */
class OrganizeLayoutPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(rootKey: String): OrganizeLayout {
        val raw = prefs.getString(key(rootKey), null) ?: return OrganizeLayout(rootKey = rootKey)
        return runCatching { decode(rootKey, raw) }.getOrElse { OrganizeLayout(rootKey = rootKey) }
    }

    fun set(layout: OrganizeLayout) {
        prefs.edit().putString(key(layout.rootKey), encode(layout)).apply()
    }

    fun remove(rootKey: String) {
        prefs.edit().remove(key(rootKey)).apply()
    }

    fun allKeys(): Set<String> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .toSet()

    private fun key(rootKey: String) = PREFIX + rootKey

    private fun encode(layout: OrganizeLayout): String {
        return JSONObject()
            .put("albumPattern", layout.albumPattern)
            .put("singlePattern", layout.singlePattern)
            .put("collision", layout.collision.name)
            .put("unsortedFolder", layout.unsortedFolder)
            .put("enabled", layout.enabled)
            .toString()
    }

    private fun decode(rootKey: String, raw: String): OrganizeLayout {
        val o = JSONObject(raw)
        val collision = runCatching {
            OrganizeLayout.CollisionPolicy.valueOf(o.optString("collision", "SUFFIX"))
        }.getOrDefault(OrganizeLayout.CollisionPolicy.SUFFIX)
        return OrganizeLayout(
            rootKey = rootKey,
            albumPattern = o.optString("albumPattern", OrganizeLayout.DEFAULT_ALBUM)
                .ifBlank { OrganizeLayout.DEFAULT_ALBUM },
            singlePattern = o.optString("singlePattern", OrganizeLayout.DEFAULT_SINGLE)
                .ifBlank { OrganizeLayout.DEFAULT_SINGLE },
            collision = collision,
            unsortedFolder = o.optString("unsortedFolder", "_unsorted").ifBlank { "_unsorted" },
            enabled = o.optBoolean("enabled", true)
        )
    }

    companion object {
        private const val PREFS = "organize_layouts"
        private const val PREFIX = "root:"
    }
}
