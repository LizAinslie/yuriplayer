package capital.yuri.yuriplayer.data

import android.content.Context
import android.os.Environment
import capital.yuri.yuriplayer.components.theme.AccentCatalog
import capital.yuri.yuriplayer.components.theme.ThemeMode
import capital.yuri.yuriplayer.data.theme.ArtColorSurface
import capital.yuri.yuriplayer.data.theme.ArtColorVariant
import capital.yuri.yuriplayer.player.engine.PlaybackEngineId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _colorPrefsRevision = MutableStateFlow(0L)
    /** Ticks when cover/banner color variants change so themed pages re-resolve once. */
    val colorPrefsRevision: StateFlow<Long> = _colorPrefsRevision.asStateFlow()

    fun useSystemColors(): Boolean = prefs.getBoolean(KEY_SYSTEM_COLORS, true)

    fun setUseSystemColors(enabled: Boolean) {
        if (enabled == useSystemColors()) return
        prefs.edit().putBoolean(KEY_SYSTEM_COLORS, enabled).apply()
        bumpColorPrefs()
    }

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

    // ── background remote sync ────────────────────────────────────────────

    fun isProfileSyncEnabled(): Boolean =
        prefs.getBoolean(KEY_PROFILE_SYNC, true)

    fun setProfileSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROFILE_SYNC, enabled).apply()
    }

    fun getProfileSyncInterval(): SyncInterval =
        SyncInterval.fromId(
            prefs.getString(KEY_PROFILE_SYNC_INTERVAL, SyncInterval.DEFAULT_PROFILE.id)
        ).takeIf { it.isActive } ?: SyncInterval.DEFAULT_PROFILE

    fun setProfileSyncInterval(interval: SyncInterval) {
        val next = interval.takeIf { it.isActive } ?: SyncInterval.DEFAULT_PROFILE
        prefs.edit().putString(KEY_PROFILE_SYNC_INTERVAL, next.id).apply()
    }

    fun isPartialSyncEnabled(): Boolean =
        prefs.getBoolean(KEY_PARTIAL_SYNC, true)

    fun setPartialSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PARTIAL_SYNC, enabled).apply()
    }

    fun getPartialSyncInterval(): SyncInterval =
        SyncInterval.fromId(
            prefs.getString(KEY_PARTIAL_SYNC_INTERVAL, SyncInterval.DEFAULT_PARTIAL.id)
        ).takeIf { it.isActive } ?: SyncInterval.DEFAULT_PARTIAL

    fun setPartialSyncInterval(interval: SyncInterval) {
        val next = interval.takeIf { it.isActive } ?: SyncInterval.DEFAULT_PARTIAL
        prefs.edit().putString(KEY_PARTIAL_SYNC_INTERVAL, next.id).apply()
    }

    fun lastProfileSyncAt(): Long = prefs.getLong(KEY_LAST_PROFILE_SYNC, 0L)

    fun markProfileSynced(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_PROFILE_SYNC, atMs).apply()
    }

    fun lastPartialSyncAt(sourceInstanceId: Long): Long =
        prefs.getLong(partialKey(sourceInstanceId), 0L)

    fun markPartialSynced(sourceInstanceId: Long, atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(partialKey(sourceInstanceId), atMs).apply()
    }

    private fun partialKey(sourceInstanceId: Long) = "$KEY_LAST_PARTIAL_SYNC.$sourceInstanceId"

    // ── playback engine ───────────────────────────────────────────────────

    /**
     * Single backend for **all** playback (local files + remote streams).
     * Android defaults to [PlaybackEngineId.VLC]; Media3 is an advanced override.
     */
    fun getPlaybackEngineId(): PlaybackEngineId =
        PlaybackEngineId.fromId(prefs.getString(KEY_PLAYBACK_ENGINE, PlaybackEngineId.VLC.id))

    fun setPlaybackEngineId(id: PlaybackEngineId) {
        prefs.edit().putString(KEY_PLAYBACK_ENGINE, id.id).apply()
    }

    // ── streaming quality (Jellyfin / Subsonic buffer + play) ─────────────

    private val _streamQuality = MutableStateFlow(readStreamQuality())
    val streamQuality: StateFlow<StreamQuality> = _streamQuality.asStateFlow()

    fun getStreamQuality(): StreamQuality = _streamQuality.value

    fun setStreamQuality(quality: StreamQuality) {
        if (quality == _streamQuality.value) return
        prefs.edit().putString(KEY_STREAM_QUALITY, quality.id).apply()
        _streamQuality.value = quality
        StreamQuality.active = quality
    }

    private fun readStreamQuality(): StreamQuality {
        val q = StreamQuality.fromId(prefs.getString(KEY_STREAM_QUALITY, StreamQuality.ORIGINAL.id))
        StreamQuality.active = q
        return q
    }

    // ── appearance / artwork colors ───────────────────────────────────────

    fun getCoverColorVariant(): ArtColorVariant =
        ArtColorVariant.fromId(prefs.getString(KEY_COVER_COLOR_VARIANT, ArtColorVariant.AUTO.id))

    fun setCoverColorVariant(variant: ArtColorVariant) {
        if (variant == getCoverColorVariant()) return
        prefs.edit().putString(KEY_COVER_COLOR_VARIANT, variant.id).apply()
        bumpColorPrefs()
    }

    fun getBannerColorVariant(): ArtColorVariant =
        ArtColorVariant.fromId(prefs.getString(KEY_BANNER_COLOR_VARIANT, ArtColorVariant.AUTO.id))

    fun setBannerColorVariant(variant: ArtColorVariant) {
        if (variant == getBannerColorVariant()) return
        prefs.edit().putString(KEY_BANNER_COLOR_VARIANT, variant.id).apply()
        bumpColorPrefs()
    }

    fun variantFor(surface: ArtColorSurface): ArtColorVariant =
        when (surface) {
            ArtColorSurface.COVER -> getCoverColorVariant()
            ArtColorSurface.BANNER -> getBannerColorVariant()
        }

    fun getThemeMode(): ThemeMode = ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.id))

    fun setThemeMode(mode: ThemeMode) {
        if (mode == getThemeMode()) return
        prefs.edit().putString(KEY_THEME_MODE, mode.id).apply()
        bumpColorPrefs()
    }

    fun getAccentId(): String = prefs.getString(KEY_ACCENT, AccentCatalog.yuri.id) ?: AccentCatalog.yuri.id

    fun setAccentId(id: String) {
        val next = AccentCatalog.byId(id).id
        if (next == getAccentId()) return
        prefs.edit().putString(KEY_ACCENT, next).apply()
        bumpColorPrefs()
    }

    fun enabledHomeRows(): Set<HomeRowId> {
        val raw = prefs.getString(KEY_HOME_ROWS, null)
        if (raw.isNullOrBlank()) return HomeRowId.entries.toSet()
        val ids = raw.split(',').map { it.trim() }.toSet()
        val parsed = HomeRowId.entries.filter { it.id in ids }.toSet()
        return parsed.ifEmpty { HomeRowId.entries.toSet() }
    }

    fun setHomeRowEnabled(id: HomeRowId, enabled: Boolean) {
        val next = enabledHomeRows().toMutableSet()
        if (enabled) next.add(id) else next.remove(id)
        prefs.edit().putString(KEY_HOME_ROWS, next.joinToString(",") { it.id }).apply()
        bumpColorPrefs()
    }

    private fun bumpColorPrefs() {
        _colorPrefsRevision.value = System.currentTimeMillis()
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
        private const val KEY_PROFILE_SYNC = "profile_sync_enabled"
        private const val KEY_PROFILE_SYNC_INTERVAL = "profile_sync_interval"
        private const val KEY_PARTIAL_SYNC = "partial_sync_enabled"
        private const val KEY_PARTIAL_SYNC_INTERVAL = "partial_sync_interval"
        private const val KEY_LAST_PROFILE_SYNC = "last_profile_sync_at"
        private const val KEY_LAST_PARTIAL_SYNC = "last_partial_sync_at"
        private const val KEY_PLAYBACK_ENGINE = "playback_engine_id"
        private const val KEY_STREAM_QUALITY = "stream_quality"
        private const val KEY_COVER_COLOR_VARIANT = "cover_color_variant"
        private const val KEY_BANNER_COLOR_VARIANT = "banner_color_variant"
        private const val KEY_SYSTEM_COLORS = "system_colors"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent_id"
        private const val KEY_HOME_ROWS = "home_rows"

        val DEFAULT_ROOTS = listOf(
            "Music",
            "Music/library",
            "Download"
        )
    }
}
