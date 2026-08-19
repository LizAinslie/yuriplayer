package capital.yuri.yuriplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Persistent catalog = **local library** + anything the user put in **My Stuff**.
 *
 * External Explore results (Jellyfin / Navidrome / …) stay ephemeral in memory
 * until the user saves them; then they land here with their source fields set.
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

    @Query(
        "SELECT COUNT(*) FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL))"
    )
    suspend fun countTracksForSource(sourceType: String, sourceInstanceId: Long?): Int

    @Query("SELECT * FROM catalog_tracks WHERE songKey = :songKey LIMIT 1")
    suspend fun getTrack(songKey: String): CatalogTrackEntity?

    @Query("SELECT * FROM catalog_tracks WHERE albumKey = :albumKey ORDER BY discNumber, trackNumber, title")
    suspend fun getTracksForAlbum(albumKey: String): List<CatalogTrackEntity>

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

    @Query("DELETE FROM catalog_tracks WHERE songKey = :songKey")
    suspend fun deleteTrack(songKey: String)

    // ── Albums ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAlbums(): Flow<List<CatalogAlbumEntity>>

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    suspend fun getAllAlbums(): List<CatalogAlbumEntity>

    @Query("SELECT * FROM catalog_albums WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbum(albumKey: String): CatalogAlbumEntity?

    @Query("SELECT * FROM catalog_albums WHERE artistKey = :artistKey ORDER BY year DESC, name COLLATE NOCASE")
    suspend fun getAlbumsForArtist(artistKey: String): List<CatalogAlbumEntity>

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
