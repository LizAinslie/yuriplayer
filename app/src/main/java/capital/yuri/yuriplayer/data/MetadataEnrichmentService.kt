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
 * Only runs when the user has opted in via [LibrarySettings.isNetworkMetadataEnabled].
 * INTERNET is not a runtime permission on Android — the opt-in is our consent UI.
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

    /**
     * Background-enrich albums missing year and/or real local art.
     * No-ops if the user has not allowed network metadata.
     */
    fun enrichLibraryAsync(maxAlbums: Int = 60) {
        if (!settings.isNetworkMetadataEnabled()) {
            Log.i(TAG, "skip enrich — network metadata not enabled")
            return
        }
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

    fun enrichAlbumAsync(album: AlbumItem) {
        if (!settings.isNetworkMetadataEnabled()) return
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

    /** Same filename scheme [AlbumArtResolver] uses for offline cover files. */
    fun coverFileForAlbumKey(key: String): File =
        File(coverDir, sanitizeFileName(key) + ".jpg")

    private fun needsWork(album: AlbumItem): Boolean {
        val key = albumKey(album.name, album.artist)
        if (key in inFlight) return false
        val hasYear = album.songs.any { it.year != null && it.year > 0 }
        // Do NOT trust MediaStore albumArtUri — often points at empty placeholders.
        val hasRealArt = album.songs.any { hasEmbeddedOrFolderArt(it.path) } ||
            coverFileForAlbumKey(key).isFile
        return !hasYear || !hasRealArt
    }

    private suspend fun enrichAlbum(album: AlbumItem) {
        val key = albumKey(album.name, album.artist)
        if (!inFlight.add(key)) return
        try {
            val existing = dao.get(key)
            if (existing != null && existing.lookupFailed) {
                existing.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            val needYear = album.songs.none { it.year != null && it.year > 0 } &&
                existing?.year == null
            val needArt = !coverFileForAlbumKey(key).isFile &&
                album.songs.none { hasEmbeddedOrFolderArt(it.path) }

            if (!needYear && !needArt) {
                existing?.year?.let { library.applyAlbumYear(key, it) }
                return
            }

            Log.i(TAG, "lookup \"${album.displayName}\" / \"${album.displayArtist}\"")
            val hit = client.searchRelease(album.artist, album.name)
            if (hit == null) {
                // Retry with track-artist if album artist failed
                val trackArtist = album.songs.firstOrNull()?.artist
                val retry = if (!trackArtist.isNullOrBlank() &&
                    !trackArtist.equals(album.artist, ignoreCase = true)
                ) {
                    client.searchRelease(trackArtist, album.name)
                } else null

                if (retry == null) {
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
                applyHit(key, album, existing, retry, needYear, needArt)
            } else {
                applyHit(key, album, existing, hit, needYear, needArt)
            }
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
        if (needArt) {
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
