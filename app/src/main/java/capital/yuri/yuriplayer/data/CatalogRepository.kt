package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.db.CatalogAlbumEntity
import capital.yuri.yuriplayer.data.db.CatalogArtistEntity
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CatalogRepository(
    private val dao: CatalogDao,
    private val musicRepository: MusicRepository
) {

    fun observeTracks(): Flow<List<Song>> =
        dao.observeTracks().map { rows -> rows.map { it.toSong() } }

    fun observeAlbums(): Flow<List<CatalogAlbumEntity>> = dao.observeAlbums()

    fun observeArtists(): Flow<List<CatalogArtistEntity>> = dao.observeArtists()

    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        dao.getAllTracks().map { it.toSong() }
    }

    /** Device library only — used for fast cold-start of [LibraryIndex]. */
    suspend fun getLocalSongs(): List<Song> = withContext(Dispatchers.IO) {
        dao.getTracksBySource(CatalogSources.LOCAL).map { it.toSong() }
    }

    suspend fun getAlbum(albumKey: String): CatalogAlbumEntity? = withContext(Dispatchers.IO) {
        dao.getAlbum(albumKey)
    }

    suspend fun getArtist(artistKey: String): CatalogArtistEntity? = withContext(Dispatchers.IO) {
        dao.getArtist(artistKey)
    }

    suspend fun countTracksForSource(sourceType: String, sourceInstanceId: Long?): Int =
        withContext(Dispatchers.IO) {
            dao.countTracksForSource(sourceType, sourceInstanceId)
        }

    suspend fun syncLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = musicRepository.scanLibrary()
        val seenAt = System.currentTimeMillis()
        val trackEntities = scanned.map { it.toLocalTrackEntity(seenAt) }

        dao.upsertTracks(trackEntities)
        dao.pruneStaleTracks(CatalogSources.LOCAL, null, seenAt)

        // Rollups are expensive — only rebuild when the local set actually changed size a lot,
        // or always once at end of local scan (still cheaper than per-page remote).
        rebuildRollupsLocked()

        val local = dao.getTracksBySource(CatalogSources.LOCAL).map { it.toSong() }
        Log.i(TAG, "local sync: ${local.size} local tracks")
        local
    }

    suspend fun ingestRemoteBatch(
        songs: List<Song>,
        sourceType: String,
        sourceInstanceId: Long?,
        seenAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val entities = songs.map { song ->
            song.toTrackEntity(
                sourceType = sourceType,
                sourceInstanceId = sourceInstanceId,
                externalId = song.path ?: song.contentUri.toString(),
                seenAt = seenAt
            )
        }
        dao.upsertTracks(entities)
    }

    suspend fun rebuildRollups() = withContext(Dispatchers.IO) {
        rebuildRollupsLocked()
    }

    private suspend fun rebuildRollupsLocked() {
        val all = dao.getAllTracks()
        val prevAlbums = dao.getAllAlbums().associateBy { it.albumKey }
        val prevArtists = dao.getAllArtists().associateBy { it.artistKey }
        dao.upsertAlbums(buildAlbumRollups(all, prevAlbums))
        dao.upsertArtists(buildArtistRollups(all, prevArtists))
        dao.deleteOrphanAlbums()
        dao.deleteOrphanArtists()
        Log.i(TAG, "rollups rebuilt: tracks=${all.size}")
    }

    suspend fun pruneRemoteSource(
        sourceType: String,
        sourceInstanceId: Long?,
        beforeMs: Long,
        keepSongKeys: Set<String>
    ) = withContext(Dispatchers.IO) {
        val stale = dao.getTracksBySource(sourceType)
            .filter {
                (sourceInstanceId == null || it.sourceInstanceId == sourceInstanceId) &&
                    it.lastSeenAtMs < beforeMs &&
                    it.songKey !in keepSongKeys
            }
        stale.forEach { dao.deleteTrack(it.songKey) }
        if (stale.isNotEmpty()) {
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            Log.i(TAG, "pruned ${stale.size} stale $sourceType rows (kept ${keepSongKeys.size} My Stuff keys)")
        }
    }

    suspend fun getRemoteOfferings(): List<SourceOffering> = withContext(Dispatchers.IO) {
        dao.getAllTracks()
            .filter { it.sourceType != CatalogSources.LOCAL }
            .map { row ->
                SourceOffering(
                    sourceType = SourceType.from(row.sourceType),
                    sourceId = row.sourceInstanceId,
                    sourceName = row.sourceType.lowercase().replaceFirstChar { it.titlecase() },
                    song = row.toSong()
                )
            }
    }

    suspend fun importToMyStuff(song: Song, sourceType: String, sourceInstanceId: Long?, externalId: String?) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entity = song.toTrackEntity(
                sourceType = sourceType,
                sourceInstanceId = sourceInstanceId,
                externalId = externalId,
                seenAt = now
            )
            dao.upsertTrack(entity)
            val all = dao.getAllTracks()
            val prevAlbums = dao.getAllAlbums().associateBy { it.albumKey }
            val prevArtists = dao.getAllArtists().associateBy { it.artistKey }
            dao.upsertAlbums(buildAlbumRollups(all, prevAlbums))
            dao.upsertArtists(buildArtistRollups(all, prevArtists))
            Log.i(TAG, "imported to My Stuff: ${entity.songKey} from $sourceType")
        }

    suspend fun applyAlbumYear(albumKey: String, year: Int) = withContext(Dispatchers.IO) {
        if (year !in 1000..2100) return@withContext
        val album = dao.getAlbum(albumKey) ?: return@withContext
        dao.upsertAlbum(album.copy(year = year, updatedAtMs = System.currentTimeMillis()))
        val tracks = dao.getTracksForAlbum(albumKey).map { t ->
            if (t.year == null || t.year <= 0) t.copy(year = year) else t
        }
        if (tracks.isNotEmpty()) dao.upsertTracks(tracks)
    }

    suspend fun applyAlbumCover(albumKey: String, coverPath: String?, coverUrl: String?, mbid: String?) =
        withContext(Dispatchers.IO) {
            val album = dao.getAlbum(albumKey) ?: return@withContext
            dao.upsertAlbum(
                album.copy(
                    coverPath = coverPath ?: album.coverPath,
                    coverUrl = coverUrl ?: album.coverUrl,
                    mbid = mbid ?: album.mbid,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }

    suspend fun applyArtistImage(artistKey: String, imageUri: String?) = withContext(Dispatchers.IO) {
        val artist = dao.getArtist(artistKey) ?: return@withContext
        if (imageUri.isNullOrBlank()) return@withContext
        dao.upsertArtist(
            artist.copy(imageUri = imageUri, updatedAtMs = System.currentTimeMillis())
        )
    }

    suspend fun coverPathForAlbum(albumKey: String): String? =
        dao.getAlbum(albumKey)?.coverPath

    private fun Song.toLocalTrackEntity(seenAt: Long) = toTrackEntity(
        sourceType = CatalogSources.LOCAL,
        sourceInstanceId = null,
        externalId = null,
        seenAt = seenAt
    )

    private fun Song.toTrackEntity(
        sourceType: String,
        sourceInstanceId: Long?,
        externalId: String?,
        seenAt: Long
    ): CatalogTrackEntity {
        val aKey = albumKey(album, effectiveAlbumArtist)
        val rKey = artistKey(effectiveAlbumArtist)
        return CatalogTrackEntity(
            songKey = songKey,
            sourceType = sourceType,
            sourceInstanceId = sourceInstanceId,
            externalId = externalId,
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            albumKey = aKey.takeIf { album != null },
            artistKey = rKey,
            contentUri = contentUri.toString(),
            path = path,
            albumArtUri = albumArtUri?.toString(),
            mimeType = mimeType,
            isTagged = isTagged,
            updatedAtMs = seenAt,
            lastSeenAtMs = seenAt
        )
    }

    private fun CatalogTrackEntity.toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        durationMs = durationMs,
        contentUri = android.net.Uri.parse(contentUri),
        albumArtUri = albumArtUri?.let { android.net.Uri.parse(it) },
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        path = path,
        mimeType = mimeType
    )

    private fun buildAlbumRollups(
        tracks: List<CatalogTrackEntity>,
        previous: Map<String, CatalogAlbumEntity> = emptyMap()
    ): List<CatalogAlbumEntity> {
        return tracks
            .filter { !it.albumKey.isNullOrBlank() }
            .groupBy { it.albumKey!! }
            .map { (key, group) ->
                val name = group.mapNotNull { it.album }.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
                val artist = group.mapNotNull { it.albumArtist ?: it.artist }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val year = group.mapNotNull { it.year }.maxOrNull()
                val artistKey = artistKey(artist)
                val coverUrl = group.mapNotNull { it.albumArtUri }.firstOrNull {
                    it.startsWith("http", ignoreCase = true)
                }
                val existing = previous[key]
                CatalogAlbumEntity(
                    albumKey = key,
                    name = name,
                    artist = artist,
                    artistKey = artistKey,
                    year = year,
                    trackCount = group.distinctBy { it.songKey }.size,
                    releaseType = guessReleaseType(group.size).name,
                    mbid = existing?.mbid,
                    coverPath = existing?.coverPath,
                    coverUrl = coverUrl ?: existing?.coverUrl,
                    primarySourceType = group.firstOrNull()?.sourceType ?: CatalogSources.LOCAL,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
    }

    private fun buildArtistRollups(
        tracks: List<CatalogTrackEntity>,
        previous: Map<String, CatalogArtistEntity> = emptyMap()
    ): List<CatalogArtistEntity> {
        return tracks
            .filter { !it.artistKey.isNullOrBlank() }
            .groupBy { it.artistKey!! }
            .map { (key, group) ->
                val display = group.mapNotNull { it.albumArtist ?: it.artist }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: key
                val albums = group.mapNotNull { it.albumKey }.toSet()
                val existing = previous[key]
                CatalogArtistEntity(
                    artistKey = key,
                    displayName = display,
                    trackCount = group.distinctBy { it.songKey }.size,
                    albumCount = albums.size,
                    bio = existing?.bio,
                    imageUri = existing?.imageUri,
                    websiteUrl = existing?.websiteUrl,
                    linksJson = existing?.linksJson,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
    }

    companion object {
        private const val TAG = "CatalogRepo"
    }
}
