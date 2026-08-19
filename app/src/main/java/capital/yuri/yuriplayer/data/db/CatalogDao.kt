package capital.yuri.yuriplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Light album rollup row produced entirely in SQLite (no full-track load). */
data class AlbumAggregateRow(
    val albumKey: String,
    val name: String?,
    val artist: String?,
    val year: Int?,
    val trackCount: Int,
    val coverUrl: String?,
    val primarySourceType: String
)

/** Light artist rollup row produced entirely in SQLite. */
data class ArtistAggregateRow(
    val artistKey: String,
    val displayName: String,
    val trackCount: Int,
    val albumCount: Int
)

/**
 * Persistent catalog = local library + remote-indexed tracks (Jellyfin / Subsonic).
 * Search and key lookups must stay SQL — never load the full table onto Main.
 *
 * Rollups are rebuilt via GROUP BY aggregates, not by pulling every track into RAM.
 */
@Dao
interface CatalogDao {

    // ── Tracks ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM catalog_tracks ORDER BY title COLLATE NOCASE")
    fun observeTracks(): Flow<List<CatalogTrackEntity>>

    @Query("SELECT * FROM catalog_tracks ORDER BY title COLLATE NOCASE")
    suspend fun getAllTracks(): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE sourceType = :sourceType")
    suspend fun getTracksBySource(sourceType: String): List<CatalogTrackEntity>

    /** Bounded sample for art pass / diagnostics — never load a whole remote library. */
    @Query("SELECT * FROM catalog_tracks WHERE sourceType = :sourceType LIMIT :limit")
    suspend fun getTracksBySourceLimited(sourceType: String, limit: Int): List<CatalogTrackEntity>

    @Query(
        "SELECT COUNT(*) FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL))"
    )
    suspend fun countTracksForSource(sourceType: String, sourceInstanceId: Long?): Int

    @Query("SELECT COUNT(*) FROM catalog_tracks WHERE sourceType != :localType")
    suspend fun countNonLocalTracks(localType: String = CatalogSources.LOCAL): Int

    @Query("SELECT * FROM catalog_tracks WHERE songKey = :songKey LIMIT 1")
    suspend fun getTrack(songKey: String): CatalogTrackEntity?

    @Query("SELECT * FROM catalog_tracks WHERE songKey IN (:keys)")
    suspend fun getTracksByKeys(keys: List<String>): List<CatalogTrackEntity>

    /**
     * Bounded multi-source lookup for one logical track (title + artist + album).
     * Used for E / multi-source badges and Sources sheet — never full-table.
     */
    @Query(
        """
        SELECT * FROM catalog_tracks
        WHERE IFNULL(title, '') = :title COLLATE NOCASE
          AND IFNULL(COALESCE(albumArtist, artist), '') = :artist COLLATE NOCASE
          AND IFNULL(album, '') = :album COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun findTracksMatching(
        title: String,
        artist: String,
        album: String,
        limit: Int = 12
    ): List<CatalogTrackEntity>

    @Query(
        "SELECT * FROM catalog_tracks WHERE " +
            "title LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR artist LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR albumArtist LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR album LIKE '%' || :q || '%' COLLATE NOCASE " +
            "LIMIT :limit"
    )
    suspend fun searchTracks(q: String, limit: Int): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE albumKey = :albumKey ORDER BY discNumber, trackNumber, title")
    suspend fun getTracksForAlbum(albumKey: String): List<CatalogTrackEntity>

    /** Single seed track for list cover art — avoids loading the full album. */
    @Query(
        "SELECT * FROM catalog_tracks WHERE albumKey = :albumKey " +
            "ORDER BY CASE WHEN albumArtUri IS NOT NULL AND albumArtUri != '' THEN 0 ELSE 1 END, " +
            "discNumber, trackNumber LIMIT 1"
    )
    suspend fun getOneTrackForAlbum(albumKey: String): CatalogTrackEntity?

    @Query("SELECT * FROM catalog_tracks WHERE artistKey = :artistKey ORDER BY album, trackNumber, title")
    suspend fun getTracksForArtist(artistKey: String): List<CatalogTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<CatalogTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: CatalogTrackEntity): Long

    @Query(
        "DELETE FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND lastSeenAtMs < :beforeMs"
    )
    suspend fun pruneStaleTracks(sourceType: String, sourceInstanceId: Long?, beforeMs: Long): Int

    /**
     * Prune one remote source without loading rows into the app process.
     * [keepKeys] is typically My Stuff song pins (small).
     */
    @Query(
        "DELETE FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND lastSeenAtMs < :beforeMs " +
            "AND songKey NOT IN (:keepKeys)"
    )
    suspend fun pruneStaleTracksExcept(
        sourceType: String,
        sourceInstanceId: Long?,
        beforeMs: Long,
        keepKeys: List<String>
    ): Int

    @Query("DELETE FROM catalog_tracks WHERE songKey = :songKey")
    suspend fun deleteTrack(songKey: String)

    // ── SQL rollups (never SELECT * tracks into Kotlin) ─────────────────

    @Query(
        """
        SELECT albumKey AS albumKey,
               MAX(album) AS name,
               MAX(COALESCE(albumArtist, artist)) AS artist,
               MAX(year) AS year,
               COUNT(DISTINCT songKey) AS trackCount,
               MAX(CASE WHEN albumArtUri LIKE 'http%' THEN albumArtUri ELSE NULL END) AS coverUrl,
               MIN(sourceType) AS primarySourceType
        FROM catalog_tracks
        WHERE albumKey IS NOT NULL AND albumKey != ''
        GROUP BY albumKey
        """
    )
    suspend fun aggregateAlbums(): List<AlbumAggregateRow>

    @Query(
        """
        SELECT artistKey AS artistKey,
               MAX(COALESCE(albumArtist, artist, artistKey)) AS displayName,
               COUNT(DISTINCT songKey) AS trackCount,
               COUNT(DISTINCT albumKey) AS albumCount
        FROM catalog_tracks
        WHERE artistKey IS NOT NULL AND artistKey != ''
        GROUP BY artistKey
        """
    )
    suspend fun aggregateArtists(): List<ArtistAggregateRow>

    // ── Albums ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAlbums(): Flow<List<CatalogAlbumEntity>>

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    suspend fun getAllAlbums(): List<CatalogAlbumEntity>

    @Query("SELECT * FROM catalog_albums WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbum(albumKey: String): CatalogAlbumEntity?

    @Query("SELECT * FROM catalog_albums WHERE artistKey = :artistKey ORDER BY year DESC, name COLLATE NOCASE")
    suspend fun getAlbumsForArtist(artistKey: String): List<CatalogAlbumEntity>

    @Query(
        "SELECT * FROM catalog_albums WHERE " +
            "name LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR artist LIKE '%' || :q || '%' COLLATE NOCASE " +
            "LIMIT :limit"
    )
    suspend fun searchAlbums(q: String, limit: Int): List<CatalogAlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(album: CatalogAlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<CatalogAlbumEntity>)

    @Query("DELETE FROM catalog_albums WHERE albumKey = :albumKey")
    suspend fun deleteAlbum(albumKey: String)

    // ── Artists ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM catalog_artists ORDER BY displayName COLLATE NOCASE")
    fun observeArtists(): Flow<List<CatalogArtistEntity>>

    @Query("SELECT * FROM catalog_artists ORDER BY displayName COLLATE NOCASE")
    suspend fun getAllArtists(): List<CatalogArtistEntity>

    @Query("SELECT * FROM catalog_artists WHERE artistKey = :artistKey LIMIT 1")
    suspend fun getArtist(artistKey: String): CatalogArtistEntity?

    @Query(
        "SELECT * FROM catalog_artists WHERE " +
            "displayName LIKE '%' || :q || '%' COLLATE NOCASE " +
            "LIMIT :limit"
    )
    suspend fun searchArtists(q: String, limit: Int): List<CatalogArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(artist: CatalogArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtists(artists: List<CatalogArtistEntity>)

    @Query("DELETE FROM catalog_artists WHERE artistKey = :artistKey")
    suspend fun deleteArtist(artistKey: String)

    // ── Bulk local sync ─────────────────────────────────────────────────

    @Transaction
    suspend fun replaceLocalCatalog(
        tracks: List<CatalogTrackEntity>,
        albums: List<CatalogAlbumEntity>,
        artists: List<CatalogArtistEntity>,
        seenAtMs: Long
    ) {
        upsertTracks(tracks)
        upsertAlbums(albums)
        upsertArtists(artists)
        pruneStaleTracks(CatalogSources.LOCAL, null, seenAtMs)
        deleteOrphanAlbums()
        deleteOrphanArtists()
    }

    @Query(
        "DELETE FROM catalog_albums WHERE albumKey NOT IN " +
            "(SELECT DISTINCT albumKey FROM catalog_tracks WHERE albumKey IS NOT NULL)"
    )
    suspend fun deleteOrphanAlbums()

    @Query(
        "DELETE FROM catalog_artists WHERE artistKey NOT IN " +
            "(SELECT DISTINCT artistKey FROM catalog_tracks WHERE artistKey IS NOT NULL)"
    )
    suspend fun deleteOrphanArtists()
}
