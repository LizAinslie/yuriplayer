package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.db.CatalogAlbumEntity
import capital.yuri.yuriplayer.data.db.CatalogArtistEntity
import capital.yuri.yuriplayer.data.db.CatalogDao
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persistent music catalog.
 *
 * ## What lives in Room
 * - **Local files** — full scan upserted on every library refresh
 * - **My Stuff** — tracks/albums/artists the user explicitly saved from an
 *   external Explore source (Jellyfin, Navidrome, …)
 *
 * ## What does *not* live in Room
 * - Browsing an external server in Explore is **ephemeral**. Results stay in
 *   memory for the session. Only [importToMyStuff] copies rows into the DB.
 *
 * Enrichment (MusicBrainz year / cover) updates existing local/My-Stuff rows;
 * it never invents catalog entries for remote-only browse results.
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
     * rollups for those tracks. Remote My-Stuff rows are left alone.
     */
    suspend fun syncLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = musicRepository.scanLibrary()
        val seenAt = System.currentTimeMillis()
        val trackEntities = scanned.map { it.toLocalTrackEntity(seenAt) }

        val albums = buildAlbumRollups(trackEntities)
        val artists = buildArtistRollups(trackEntities)

        // Keep remote My-Stuff tracks; only rebuild rollups from *all* tracks
        // after local upsert + prune.
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
     * Persist an external Explore item into My Stuff / catalog.
     * Call this when the user favorites, adds to a playlist, or explicitly
     * "Save to library". Until then, remote browse data must not hit Room.
     */
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
            // Refresh rollups for this album/artist so Explore + My Stuff see them
            val all = dao.getAllTracks()
            dao.upsertAlbums(buildAlbumRollups(all))
            dao.upsertArtists(buildArtistRollups(all))
            Log.i(TAG, "imported to My Stuff: ${entity.songKey} from $sourceType")
        }

    /** Apply MusicBrainz (or other) year onto a persisted album + its tracks. */
    suspend fun applyAlbumYear(albumKey: String, year: Int) = withContext(Dispatchers.IO) {
        if (year !in 1000..2100) return@withContext
        val album = dao.getAlbum(albumKey) ?: return@withContext
        dao.upsertAlbum(album.copy(year = year, updatedAtMs = System.currentTimeMillis()))
        val tracks = dao.getTracksForAlbum(albumKey).map { t ->
            if (t.year == null || t.year <= 0) t.copy(year = year) else t
        }
        if (tracks.isNotEmpty()) dao.upsertTracks(tracks)
    }

    /** Apply downloaded cover path onto a persisted album. */
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

    // ── mapping helpers ─────────────────────────────────────────────────

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
