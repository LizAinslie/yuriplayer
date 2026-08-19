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

/**
 * Persistent music catalog.
 *
 * ## What lives in Room
 * - **Local files** — full scan upserted on every library refresh
 * - **Remote libraries** — source-tagged rows from Jellyfin / Subsonic scans
 *   (progressive ingest). Stale remote rows are pruned after a scan unless the
 *   songKey is kept for My Stuff.
 * - **My Stuff** — explicit saves; protected from remote prune
 *
 * Enrichment (MusicBrainz year / cover) updates existing rows.
 */
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

    /**
     * Full local rescan → replace LOCAL rows in Room and rebuild album/artist
     * rollups. Remote rows are left alone.
     */
    suspend fun syncLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = musicRepository.scanLibrary()
        val seenAt = System.currentTimeMillis()
        val trackEntities = scanned.map { it.toLocalTrackEntity(seenAt) }

        dao.upsertTracks(trackEntities)
        dao.pruneStaleTracks(CatalogSources.LOCAL, null, seenAt)

        val allTracks = dao.getAllTracks()
        dao.upsertAlbums(buildAlbumRollups(allTracks))
        dao.upsertArtists(buildArtistRollups(allTracks))
        dao.deleteOrphanAlbums()
        dao.deleteOrphanArtists()

        Log.i(TAG, "local sync: ${trackEntities.size} local tracks, catalog total=${allTracks.size}")
        allTracks.map { it.toSong() }
    }

    /**
     * Upsert a page of remote songs into the catalog (source-tagged).
     * Called live during progressive scans.
     */
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
        // Cheap rollup refresh for this batch only would miss merges; periodic full
        // rebuild is fine for Explore — do a light album upsert from batch keys.
        val all = dao.getAllTracks()
        dao.upsertAlbums(buildAlbumRollups(all))
        dao.upsertArtists(buildArtistRollups(all))
    }

    /**
     * After a remote scan, drop rows for that source that were not seen,
     * **except** songKeys the user has in My Stuff (those stay indexed without
     * requiring the remote still list them).
     */
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

    /** Rebuild live Explore offerings from persisted non-local catalog rows. */
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
            dao.upsertAlbums(buildAlbumRollups(all))
            dao.upsertArtists(buildArtistRollups(all))
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

    private fun buildAlbumRollups(tracks: List<CatalogTrackEntity>): List<CatalogAlbumEntity> {
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
                CatalogAlbumEntity(
                    albumKey = key,
                    name = name,
                    artist = artist,
                    artistKey = artistKey,
                    year = year,
                    trackCount = group.distinctBy { it.songKey }.size,
                    releaseType = guessReleaseType(group.size).name,
                    primarySourceType = group.firstOrNull()?.sourceType ?: CatalogSources.LOCAL,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
    }

    private fun buildArtistRollups(tracks: List<CatalogTrackEntity>): List<CatalogArtistEntity> {
        return tracks
            .filter { !it.artistKey.isNullOrBlank() }
            .groupBy { it.artistKey!! }
            .map { (key, group) ->
                val display = group.mapNotNull { it.albumArtist ?: it.artist }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: key
                val albums = group.mapNotNull { it.albumKey }.toSet()
                CatalogArtistEntity(
                    artistKey = key,
                    displayName = display,
                    trackCount = group.distinctBy { it.songKey }.size,
                    albumCount = albums.size,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
    }

    companion object {
        private const val TAG = "CatalogRepo"
    }
}
