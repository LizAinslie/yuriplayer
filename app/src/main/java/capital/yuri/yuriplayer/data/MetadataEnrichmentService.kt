package capital.yuri.yuriplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import capital.yuri.yuriplayer.data.db.AlbumMetadataDao
import capital.yuri.yuriplayer.data.db.AlbumMetadataEntity
import capital.yuri.yuriplayer.data.source.MusicBrainzClient
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
 * **Manual** [enrichAlbumAsync] / [enrichAlbumsAsync] always run when the user
 * taps "Fetch additional metadata".
 * **Automatic** [enrichLibraryAsync] only runs when
 * [LibrarySettings.isAutomaticMetadataEnabled] is true.
 */
class MetadataEnrichmentService(
    private val context: Context,
    private val dao: AlbumMetadataDao,
    private val client: MusicBrainzClient,
    private val library: LibraryIndex,
    private val settings: LibrarySettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Bumped when a cover file is written so Compose can reload art. */
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

    /** Background enrich — only when automatic is enabled in Settings. */
    fun enrichLibraryAsync(maxAlbums: Int = 60) {
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
                    val albums = library.albums(taggedOnly = false)
                        .filter { needsWork(it) }
                        .take(maxAlbums)
                    Log.i(TAG, "auto enrich queue size=${albums.size}")
                    for (album in albums) {
                        enrichAlbum(album, force = false)
                    }
                    _statusMessage.value = null
                } catch (e: Exception) {
                    Log.e(TAG, "enrichLibrary failed", e)
                    _statusMessage.value = "Metadata lookup failed"
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /**
     * User-initiated fetch for one album. Always allowed (manual consent).
     * [force] retries even if a previous lookup was marked failed.
     */
    fun enrichAlbumAsync(album: AlbumItem, force: Boolean = true) {
        scope.launch {
            _busy.value = true
            _statusMessage.value = "Fetching metadata for ${album.displayName}…"
            try {
                enrichAlbum(album, force = force)
                _statusMessage.value = "Done: ${album.displayName}"
            } catch (e: Exception) {
                Log.w(TAG, "enrichAlbum failed ${album.displayName}", e)
                _statusMessage.value = "Failed: ${album.displayName}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** User-initiated fetch for several albums (artist page). */
    fun enrichAlbumsAsync(albums: List<AlbumItem>, force: Boolean = true) {
        if (albums.isEmpty()) return
        scope.launch {
            workLock.withLock {
                _busy.value = true
                _statusMessage.value = "Fetching metadata for ${albums.size} releases…"
                try {
                    for (album in albums) {
                        enrichAlbum(album, force = force)
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

    private fun needsWork(album: AlbumItem): Boolean {
        val key = albumKey(album.name, album.artist)
        if (key in inFlight) return false
        val hasYear = album.songs.any { it.year != null && it.year > 0 }
        val hasRealArt = album.songs.any { hasEmbeddedOrFolderArt(it.path) } ||
            coverFileForAlbumKey(key).isFile
        return !hasYear || !hasRealArt
    }

    private suspend fun enrichAlbum(album: AlbumItem, force: Boolean) {
        val key = albumKey(album.name, album.artist)
        if (!inFlight.add(key)) return
        try {
            val existing = dao.get(key)
            if (!force && existing != null && existing.lookupFailed) {
                existing.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            val needYear = album.songs.none { it.year != null && it.year > 0 } &&
                existing?.year == null
            val needArt = !coverFileForAlbumKey(key).isFile &&
                album.songs.none { hasEmbeddedOrFolderArt(it.path) }

            if (!force && !needYear && !needArt) {
                existing?.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            Log.i(TAG, "lookup \"${album.displayName}\" / \"${album.displayArtist}\" force=$force")
            var hit = client.searchRelease(album.artist, album.name)
            if (hit == null) {
                val trackArtist = album.songs.firstOrNull()?.artist
                if (!trackArtist.isNullOrBlank() &&
                    !trackArtist.equals(album.artist, ignoreCase = true)
                ) {
                    hit = client.searchRelease(trackArtist, album.name)
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
        if (needArt || coverPath == null) {
            val dest = coverFileForAlbumKey(key)
            if (client.downloadFrontCover(hit.mbid, dest)) {
                coverPath = dest.absolutePath
                Log.i(TAG, "cover saved $coverPath for ${album.displayName}")
                _coverGeneration.value = System.currentTimeMillis()
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
