package capital.yuri.yuriplayer.data

import android.content.Context
import android.os.Environment
import capital.yuri.yuriplayer.player.engine.PlaybackEngineId
import java.io.File

/** How local files are discovered. */
enum class LibraryScanMode {
    /** Android MediaStore (+ filesystem fill for roots MediaStore missed). */
    MEDIASTORE,

    /**
     * User-selected SAF trees only. Walks granted folders with our tag reader;
     * does not depend on the system media scanner.
     */
    MANUAL
}

/**
 * User-configurable library roots + metadata / playback preferences.
 *
 * Online year/art lookup is **manual by default** (album/artist "Fetch additional
 * metadata"). Users can enable automatic background enrichment in Settings.
 *
 * Remote library sync is **Wi‑Fi / unmetered only by default** so large Jellyfin
 * indexes don't burn through mobile data plans.
 */
class LibrarySettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── local scan mode ───────────────────────────────────────────────────

    fun getScanMode(): LibraryScanMode {
        val raw = prefs.getString(KEY_SCAN_MODE, LibraryScanMode.MEDIASTORE.name)
        return runCatching { LibraryScanMode.valueOf(raw!!) }
            .getOrDefault(LibraryScanMode.MEDIASTORE)
    }

    fun setScanMode(mode: LibraryScanMode) {
        prefs.edit().putString(KEY_SCAN_MODE, mode.name).apply()
    }

    /**
     * Legacy relative path roots used by [LibraryScanMode.MEDIASTORE] filesystem fill
     * (and as a fallback hint). Prefer absolute paths under external storage.
     */
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
        val current = prefs.getStringSet(KEY_ROOTS, DEFAULT_ROOTS.toSet())?.toMutableSet()
            ?: mutableSetOf()
        current += relativePath.trim().trimStart('/')
        prefs.edit().putStringSet(KEY_ROOTS, current).apply()
    }

    /**
     * Persistable SAF tree URIs for [LibraryScanMode.MANUAL].
     * Caller must [android.content.ContentResolver.takePersistableUriPermission] first.
     */
    fun getManualTreeUris(): List<String> =
        prefs.getStringSet(KEY_MANUAL_TREES, emptySet())?.toList()?.sorted().orEmpty()

    fun setManualTreeUris(uris: Collection<String>) {
        prefs.edit().putStringSet(
            KEY_MANUAL_TREES,
            uris.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        ).apply()
    }

    fun addManualTreeUri(uri: String) {
        val u = uri.trim()
        if (u.isEmpty()) return
        val current = prefs.getStringSet(KEY_MANUAL_TREES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current += u
        prefs.edit().putStringSet(KEY_MANUAL_TREES, current).apply()
    }

    fun removeManualTreeUri(uri: String) {
        val current = prefs.getStringSet(KEY_MANUAL_TREES, emptySet())?.toMutableSet() ?: return
        if (current.remove(uri)) {
            prefs.edit().putStringSet(KEY_MANUAL_TREES, current).apply()
        }
    }

    fun isAutomaticMetadataEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_METADATA, false)

    fun setAutomaticMetadataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_METADATA, enabled).apply()
    }

    fun isAutoPlayRecommendedEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_PLAY_RECOMMENDED, false)

    fun setAutoPlayRecommendedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PLAY_RECOMMENDED, enabled).apply()
    }

    /**
     * When false (default), remote library sync / large index downloads only run
     * on unmetered networks (typically Wi‑Fi). Playback of already-indexed remote
     * tracks is unaffected.
     */
    fun isSyncOverMobileDataEnabled(): Boolean =
        prefs.getBoolean(KEY_SYNC_OVER_MOBILE_DATA, false)

    fun setSyncOverMobileDataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_OVER_MOBILE_DATA, enabled).apply()
    }

    // ── playback engine ───────────────────────────────────────────────────

    /**
     * Single backend for **all** playback (local files + remote streams).
     * Default [PlaybackEngineId.MEDIA3]; pick [PlaybackEngineId.VLC] if FLAC
     * or odd containers fail on Media3.
     */
    fun getPlaybackEngineId(): PlaybackEngineId =
        PlaybackEngineId.fromId(prefs.getString(KEY_PLAYBACK_ENGINE, PlaybackEngineId.MEDIA3.id))

    fun setPlaybackEngineId(id: PlaybackEngineId) {
        prefs.edit().putString(KEY_PLAYBACK_ENGINE, id.id).apply()
    }

    fun networkMetadataConsent(): Boolean? = isAutomaticMetadataEnabled()

    fun isNetworkMetadataEnabled(): Boolean = isAutomaticMetadataEnabled()

    fun setNetworkMetadataConsent(enabled: Boolean) =
        setAutomaticMetadataEnabled(enabled)

    fun migrateLegacyNetworkConsentIfNeeded() {
        if (prefs.contains(KEY_NETWORK_META) && !prefs.contains(KEY_AUTO_METADATA)) {
            val legacy = prefs.getBoolean(KEY_NETWORK_META, false)
            prefs.edit()
                .putBoolean(KEY_AUTO_METADATA, legacy)
                .remove(KEY_NETWORK_META)
                .apply()
        }
        if (!prefs.contains(KEY_AUTO_METADATA)) {
            prefs.edit().putBoolean(KEY_AUTO_METADATA, false).apply()
        }
    }

    companion object {
        private const val PREFS = "library_settings"
        private const val KEY_ROOTS = "scan_roots"
        private const val KEY_SCAN_MODE = "scan_mode"
        private const val KEY_MANUAL_TREES = "manual_tree_uris"
        private const val KEY_AUTO_METADATA = "automatic_metadata_enabled"
        private const val KEY_AUTO_PLAY_RECOMMENDED = "auto_play_recommended"
        private const val KEY_NETWORK_META = "network_metadata_enabled"
        private const val KEY_SYNC_OVER_MOBILE_DATA = "sync_over_mobile_data"
        private const val KEY_PLAYBACK_ENGINE = "playback_engine_id"

        val DEFAULT_ROOTS = listOf(
            "Music",
            "Music/library",
            "Download"
        )
    }
}
