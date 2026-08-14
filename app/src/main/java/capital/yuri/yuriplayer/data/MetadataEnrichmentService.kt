package capital.yuri.yuriplayer.data

import android.content.Context
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
 * Enriched data is stored in Room + optional cover files under app storage.
 * Years are merged into [LibraryIndex] song objects so UI/sort pick them up.
 * Audio files themselves are not rewritten (safe on scoped storage / shared libs).
 */
class MetadataEnrichmentService(
    private val context: Context,
    private val dao: AlbumMetadataDao,
    private val client: MusicBrainzClient,
    private val library: LibraryIndex
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val coverDir: File
        get() = File(context.filesDir, "covers").also { it.mkdirs() }

    /** Apply already-cached metadata onto the live song list (years). */
    suspend fun applyCachedToLibrary() = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        for (row in all) {
            if (row.year != null) {
                library.applyAlbumYear(row.albumKey, row.year)
            }
        }
    }

    /**
     * Background-enrich albums that are missing year and/or local art.
     * Rate-limited by [MusicBrainzClient]; safe to call after every scan.
     */
    fun enrichLibraryAsync(maxAlbums: Int = 40) {
        scope.launch {
            workLock.withLock {
                _busy.value = true
                try {
                    applyCachedToLibrary()
                    val albums = library.albums(taggedOnly = false)
                        .filter { needsWork(it) }
                        .take(maxAlbums)
                    Log.i(TAG, "enrich queue size=${albums.size}")
                    for (album in albums) {
                        enrichAlbum(album)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "enrichLibrary failed", e)
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** Enrich a single album if needed (e.g. when opening artist/album page). */
    fun enrichAlbumAsync(album: AlbumItem) {
        scope.launch {
            try {
                enrichAlbum(album)
            } catch (e: Exception) {
                Log.w(TAG, "enrichAlbum failed ${album.displayName}", e)
            }
        }
    }

    suspend fun coverPathFor(albumKey: String): String? =
        dao.get(albumKey)?.coverPath?.takeIf { File(it).isFile }

    suspend fun yearFor(albumKey: String): Int? =
        dao.get(albumKey)?.year

    private fun needsWork(album: AlbumItem): Boolean {
        val key = albumKey(album.name, album.artist) ?: return false
        if (key in inFlight) return false
        val hasYear = album.songs.any { it.year != null && it.year > 0 }
        val hasArt = album.songs.any { song ->
            !song.albumArtUri?.toString().isNullOrBlank() ||
                folderCoverExists(song.path)
        }
        return !hasYear || !hasArt
    }

    private suspend fun enrichAlbum(album: AlbumItem) {
        val key = albumKey(album.name, album.artist) ?: return
        if (!inFlight.add(key)) return
        try {
            val existing = dao.get(key)
            if (existing != null && existing.lookupFailed) {
                // Still apply any partial data we already have.
                existing.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            val needYear = album.songs.none { it.year != null && it.year > 0 } &&
                existing?.year == null
            val needArt = existing?.coverPath?.let { File(it).isFile } != true &&
                album.songs.none { folderCoverExists(it.path) }

            if (!needYear && !needArt) {
                existing?.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            Log.i(TAG, "lookup \"${album.displayName}\" / \"${album.displayArtist}\"")
            val hit = client.searchRelease(album.artist, album.name)
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
                return
            }

            var coverPath = existing?.coverPath?.takeIf { File(it).isFile }
            if (needArt && (hit.hasFrontCover || true)) {
                val dest = File(coverDir, sanitizeFileName(key) + ".jpg")
                if (client.downloadFrontCover(hit.mbid, dest)) {
                    coverPath = dest.absolutePath
                    Log.i(TAG, "cover saved $coverPath")
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
        } finally {
            inFlight.remove(key)
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

    private fun sanitizeFileName(key: String): String =
        key.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .take(120)

    companion object {
        private const val TAG = "MetaEnrich"
    }
}
