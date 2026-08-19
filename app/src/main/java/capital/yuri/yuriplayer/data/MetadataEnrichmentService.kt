package capital.yuri.yuriplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import capital.yuri.yuriplayer.data.db.AlbumMetadataDao
import capital.yuri.yuriplayer.data.db.AlbumMetadataEntity
import capital.yuri.yuriplayer.data.source.MusicBrainzClient
import capital.yuri.yuriplayer.data.theme.ThemeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fills gaps in local tags using MusicBrainz (year) and Cover Art Archive (art).
 *
 * Manual fetch always runs; automatic only when Settings allows it.
 * New covers invalidate [AlbumArtCache] / [ThemeService] and bump [coverGeneration]
 * so UI reloads art **and** Material You colors.
 *
 * Auto-enrich stays sequential (MusicBrainz ~1 req/s) but avoids expensive local I/O
 * (no per-track MediaMetadataRetriever scans in the bulk queue).
 */
class MetadataEnrichmentService(
    private val context: Context,
    private val dao: AlbumMetadataDao,
    private val client: MusicBrainzClient,
    private val library: LibraryIndex,
    private val settings: LibrarySettings,
    private val artCache: AlbumArtCache,
    private val themeService: ThemeService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Bumped when a cover file is written so Compose can reload art + colors. */
    private val _coverGeneration = MutableStateFlow(0L)
    val coverGeneration: StateFlow<Long> = _coverGeneration.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val coverDir: File
        get() = File(context.filesDir, "covers").also { it.mkdirs() }

    suspend fun applyCachedToLibrary() = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        for (row in all) {
            if (row.year != null) {
                library.applyAlbumYear(row.albumKey, row.year)
            }
        }
    }

    fun enrichLibraryAsync(maxAlbums: Int = 80) {
        if (!settings.isAutomaticMetadataEnabled()) {
            Log.i(TAG, "skip auto enrich — automatic metadata disabled")
            return
        }
        scope.launch {
            workLock.withLock {
                _busy.value = true
                _statusMessage.value = "Looking up metadata…"
                try {
                    applyCachedToLibrary()
                    // Cheap filter only — no embedded-art probing (that was the lag).
                    val albums = library.albums(taggedOnly = false)
                        .filter { needsWorkCheap(it) }
                        .take(maxAlbums)
                    Log.i(TAG, "auto enrich queue size=${albums.size}")
                    var done = 0
                    for (album in albums) {
                        if (!settings.isAutomaticMetadataEnabled()) {
                            Log.i(TAG, "auto enrich cancelled mid-run")
                            break
                        }
                        enrichAlbum(album, force = false, probeEmbeddedArt = false)
                        done++
                        if (done % 5 == 0) {
                            _statusMessage.value = "Metadata $done / ${albums.size}…"
                        }
                    }
                    _statusMessage.value = if (done > 0) "Updated $done releases" else null
                } catch (e: Exception) {
                    Log.e(TAG, "enrichLibrary failed", e)
                    _statusMessage.value = "Metadata lookup failed"
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    fun enrichAlbumAsync(album: AlbumItem, force: Boolean = true) {
        scope.launch {
            _busy.value = true
            _statusMessage.value = "Fetching metadata for ${album.displayName}…"
            try {
                enrichAlbum(album, force = force, probeEmbeddedArt = true)
                _statusMessage.value = "Done: ${album.displayName}"
            } catch (e: Exception) {
                Log.w(TAG, "enrichAlbum failed ${album.displayName}", e)
                _statusMessage.value = "Failed: ${album.displayName}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun enrichAlbumsAsync(albums: List<AlbumItem>, force: Boolean = true) {
        if (albums.isEmpty()) return
        scope.launch {
            workLock.withLock {
                _busy.value = true
                _statusMessage.value = "Fetching metadata for ${albums.size} releases…"
                try {
                    for (album in albums) {
                        enrichAlbum(album, force = force, probeEmbeddedArt = true)
                    }
                    _statusMessage.value = "Fetched metadata for ${albums.size} releases"
                } catch (e: Exception) {
                    Log.e(TAG, "enrichAlbums failed", e)
                    _statusMessage.value = "Metadata lookup failed"
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    suspend fun coverPathFor(albumKey: String): String? =
        dao.get(albumKey)?.coverPath?.takeIf { File(it).isFile }

    fun coverFileForAlbumKey(key: String): File =
        File(coverDir, sanitizeFileName(key) + ".jpg")

    /**
     * Bulk auto-fetch gate: year from tags / Room, cover from folder file or cached download.
     * Does **not** open MediaMetadataRetriever (very slow across a whole library).
     */
    private fun needsWorkCheap(album: AlbumItem): Boolean {
        val key = albumKey(album.name, album.artist)
        if (key in inFlight) return false
        val hasYear = album.songs.any { it.year != null && it.year!! > 0 }
        val hasArt = coverFileForAlbumKey(key).isFile ||
            album.songs.any { folderCoverExists(it.path) }
        return !hasYear || !hasArt
    }

    private suspend fun enrichAlbum(
        album: AlbumItem,
        force: Boolean,
        probeEmbeddedArt: Boolean
    ) {
        val key = albumKey(album.name, album.artist)
        if (!inFlight.add(key)) return
        try {
            val existing = dao.get(key)
            if (!force && existing != null && existing.lookupFailed) {
                existing.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            val hasEmbedded = if (probeEmbeddedArt) {
                album.songs.any { hasEmbeddedOrFolderArt(it.path) }
            } else {
                album.songs.any { folderCoverExists(it.path) }
            }

            val needYear = album.songs.none { it.year != null && it.year!! > 0 } &&
                existing?.year == null
            val needArt = !coverFileForAlbumKey(key).isFile && !hasEmbedded

            if (!force && !needYear && !needArt) {
                existing?.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            Log.i(TAG, "lookup \"${album.displayName}\" / \"${album.displayArtist}\" force=$force")
            // Skip tags include on search when we only need year/cover — one fewer MB round-trip.
            var hit = client.searchRelease(
                artist = album.artist,
                album = album.name,
                includeTags = false
            )
            if (hit == null) {
                val trackArtist = album.songs.firstOrNull()?.artist
                if (!trackArtist.isNullOrBlank() &&
                    !trackArtist.equals(album.artist, ignoreCase = true)
                ) {
                    hit = client.searchRelease(trackArtist, album.name, includeTags = false)
                }
            }

            if (hit == null) {
                dao.upsert(
                    AlbumMetadataEntity(
                        id = existing?.id ?: 0,
                        albumKey = key,
                        year = existing?.year,
                        mbid = existing?.mbid,
                        coverPath = existing?.coverPath,
                        coverUrl = existing?.coverUrl,
                        lookupFailed = true,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                Log.w(TAG, "no MB hit for $key")
                return
            }

            applyHit(key, album, existing, hit, needYear || force, needArt || force)
        } finally {
            inFlight.remove(key)
        }
    }

    private suspend fun applyHit(
        key: String,
        album: AlbumItem,
        existing: AlbumMetadataEntity?,
        hit: MusicBrainzClient.ReleaseHit,
        needYear: Boolean,
        needArt: Boolean
    ) {
        var coverPath = existing?.coverPath?.takeIf { File(it).isFile }
        var coverChanged = false
        if (needArt || coverPath == null) {
            val dest = coverFileForAlbumKey(key)
            if (client.downloadFrontCover(hit.mbid, dest)) {
                coverPath = dest.absolutePath
                coverChanged = true
                Log.i(TAG, "cover saved $coverPath for ${album.displayName}")
            } else {
                Log.w(TAG, "cover download failed mbid=${hit.mbid}")
            }
        }

        val year = hit.year ?: existing?.year
        dao.upsert(
            AlbumMetadataEntity(
                id = existing?.id ?: 0,
                albumKey = key,
                year = year,
                mbid = hit.mbid,
                coverPath = coverPath,
                coverUrl = "https://coverartarchive.org/release/${hit.mbid}/front",
                source = "musicbrainz",
                lookupFailed = false,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        if (year != null) {
            library.applyAlbumYear(key, year)
        }

        if (coverChanged) {
            runCatching { artCache.invalidateAlbum(key) }
            runCatching { artCache.invalidateAllMemory() }
            themeService.invalidateAll()
            _coverGeneration.value = System.currentTimeMillis()
        }
    }

    private fun hasEmbeddedOrFolderArt(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (folderCoverExists(path)) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture != null
        } catch (_: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun folderCoverExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val dir = File(path).parentFile ?: return false
        val names = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png",
            "AlbumArt.jpg", "front.jpg"
        )
        return names.any { File(dir, it).isFile }
    }

    companion object {
        private const val TAG = "MetaEnrich"

        fun sanitizeFileName(key: String): String =
            key.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .take(120)
    }
}
