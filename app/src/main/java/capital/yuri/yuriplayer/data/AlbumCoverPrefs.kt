package capital.yuri.yuriplayer.data

import android.content.Context

/**
 * Per-release preferred cover URI.
 *
 * Keyed by [albumKey] (folded artist|album). When set, [AlbumArtResolver] /
 * [AlbumArtCache] serve this URI first; otherwise local embedded/folder art wins.
 */
class AlbumCoverPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preferredUri(albumKey: String): String? {
        if (albumKey.isBlank()) return null
        return prefs.getString(key(albumKey), null)?.takeIf { it.isNotBlank() }
    }

    fun setPreferredUri(albumKey: String, uri: String?) {
        if (albumKey.isBlank()) return
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove(key(albumKey))
            else putString(key(albumKey), uri)
        }.apply()
    }

    fun clear(albumKey: String) = setPreferredUri(albumKey, null)

    private fun key(albumKey: String) = "uri:$albumKey"

    companion object {
        private const val PREFS = "album_cover_prefs"
    }
}

// CoverCandidate moved to :core (commonMain), same package.
