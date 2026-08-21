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
 */
@Dao
interface CatalogDao {

    @Query("SELECT * FROM catalog_tracks ORDER BY title COLLATE NOCASE")
    fun observeTracks(): Flow<List<CatalogTrackEntity>>

    @Query("SELECT * FROM catalog_tracks ORDER BY title COLLATE NOCASE")
    suspend fun getAllTracks(): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE sourceType = :sourceType")
    suspend fun getTracksBySource(sourceType: String): List<CatalogTrackEntity>

    @Query(
        "SELECT * FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "ORDER BY album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE"
    )
    suspend fun getTracksForSourceInstance(
        sourceType: String,
        sourceInstanceId: Long?
    ): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE sourceType = :sourceType LIMIT :limit")
    suspend fun getTracksBySourceLimited(sourceType: String, limit: Int): List<CatalogTrackEntity>

    @Query(
        "SELECT COUNT(*) FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL))"
    )
    suspend fun countTracksForSource(sourceType: String, sourceInstanceId: Long?): Int

    @Query(
        "SELECT externalId FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND externalId IS NOT NULL"
    )
    suspend fun externalIdsForSource(sourceType: String, sourceInstanceId: Long?): List<String>

    @Query(
        "SELECT * FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND externalId IN (:ids)"
    )
    suspend fun tracksByExternalIds(
        sourceType: String,
        sourceInstanceId: Long?,
        ids: List<String>
    ): List<CatalogTrackEntity>

    @Query(
        "SELECT songKey FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND externalId IN (:ids)"
    )
    suspend fun songKeysForExternalIds(
        sourceType: String,
        sourceInstanceId: Long?,
        ids: List<String>
    ): List<String>

    @Query(
        "UPDATE catalog_tracks SET lastSeenAtMs = :seenAt WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND externalId IN (:ids)"
    )
    suspend fun touchLastSeenByExternalIds(
        sourceType: String,
        sourceInstanceId: Long?,
        ids: List<String>,
        seenAt: Long
    ): Int

    @Query("DELETE FROM catalog_tracks WHERE songKey IN (:keys)")
    suspend fun deleteTracksByKeys(keys: List<String>): Int

    @Query("DELETE FROM catalog_credits WHERE subjectType = 'TRACK' AND subjectKey IN (:keys)")
    suspend fun deleteCreditsForTracks(keys: List<String>)

    @Query("SELECT COUNT(*) FROM catalog_tracks WHERE sourceType != :localType")
    suspend fun countNonLocalTracks(localType: String = CatalogSources.LOCAL): Int

    @Query("SELECT * FROM catalog_tracks WHERE songKey = :songKey LIMIT 1")
    suspend fun getTrack(songKey: String): CatalogTrackEntity?

    @Query("SELECT * FROM catalog_tracks WHERE songKey IN (:keys)")
    suspend fun getTracksByKeys(keys: List<String>): List<CatalogTrackEntity>

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

    @Query(
        "SELECT * FROM catalog_tracks WHERE sourceType = :sourceType " +
            "AND (sourceInstanceId IS :sourceInstanceId OR (sourceInstanceId IS NULL AND :sourceInstanceId IS NULL)) " +
            "AND (title LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR artist LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR albumArtist LIKE '%' || :q || '%' COLLATE NOCASE " +
            "OR album LIKE '%' || :q || '%' COLLATE NOCASE) " +
            "LIMIT :limit"
    )
    suspend fun searchTracksForSource(
        sourceType: String,
        sourceInstanceId: Long?,
        q: String,
        limit: Int
    ): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE albumKey = :albumKey ORDER BY discNumber, trackNumber, title")
    suspend fun getTracksForAlbum(albumKey: String): List<CatalogTrackEntity>

    /** Exact album tag match, any source / albumKey. */
    @Query(
        "SELECT * FROM catalog_tracks WHERE album = :album COLLATE NOCASE " +
            "ORDER BY discNumber, trackNumber, title LIMIT :limit"
    )
    suspend fun getTracksByAlbumName(album: String, limit: Int = 500): List<CatalogTrackEntity>

    /**
     * Album page primary query: album tag equals [album] AND artist loosely matches.
     * Empty [artist] means any artist (caller filters further if needed).
     */
    @Query(
        """
        SELECT * FROM catalog_tracks
        WHERE album = :album COLLATE NOCASE
          AND (
            :artist = ''
            OR IFNULL(albumArtist, '') = :artist COLLATE NOCASE
            OR IFNULL(artist, '') = :artist COLLATE NOCASE
            OR IFNULL(albumArtist, '') LIKE '%' || :artist || '%' COLLATE NOCASE
            OR IFNULL(artist, '') LIKE '%' || :artist || '%' COLLATE NOCASE
          )
        ORDER BY discNumber, trackNumber, title
        LIMIT :limit
        """
    )
    suspend fun getTracksForAlbumNameArtist(
        album: String,
        artist: String,
        limit: Int = 1000
    ): List<CatalogTrackEntity>

    /** All albumKeys that share this album title (case-insensitive). */
    @Query(
        "SELECT DISTINCT albumKey FROM catalog_tracks " +
            "WHERE album = :album COLLATE NOCASE AND albumKey IS NOT NULL AND albumKey != ''"
    )
    suspend fun albumKeysForAlbumName(album: String): List<String>

    @Query(
        "SELECT * FROM catalog_tracks WHERE albumKey = :albumKey " +
            "ORDER BY CASE WHEN sourceType = 'LOCAL' THEN 0 ELSE 1 END, " +
            "CASE WHEN albumArtUri IS NOT NULL AND albumArtUri != '' THEN 0 ELSE 1 END, " +
            "discNumber, trackNumber LIMIT 1"
    )
    suspend fun getOneTrackForAlbum(albumKey: String): CatalogTrackEntity?

    @Query(
        """
        SELECT * FROM catalog_tracks WHERE rowid IN (
            SELECT MIN(rowid) FROM catalog_tracks
            WHERE albumKey IN (:albumKeys)
            GROUP BY albumKey
        )
        """
    )
    suspend fun oneTrackPerAlbum(albumKeys: List<String>): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE artistKey IN (:keys) ORDER BY album, trackNumber, title")
    suspend fun getTracksForArtists(keys: List<String>): List<CatalogTrackEntity>

    @Query("SELECT * FROM catalog_tracks WHERE artistKey = :artistKey ORDER BY album, trackNumber, title")
    suspend fun getTracksForArtist(artistKey: String): List<CatalogTrackEntity>

    @Query(
        """
        SELECT t.* FROM catalog_tracks t
        INNER JOIN catalog_credits c
          ON c.subjectType = 'TRACK' AND c.subjectKey = t.songKey
        WHERE c.artistKey IN (:keys) AND c.role = :role
        ORDER BY t.album, t.trackNumber, t.title
        """
    )
    suspend fun getTracksByCreditRoles(keys: List<String>, role: String): List<CatalogTrackEntity>

    @Query(
        """
        SELECT t.* FROM catalog_tracks t
        INNER JOIN catalog_credits c
          ON c.subjectType = 'TRACK' AND c.subjectKey = t.songKey
        WHERE c.artistKey = :artistKey AND c.role = :role
        ORDER BY t.album, t.trackNumber, t.title
        """
    )
    suspend fun getTracksByCreditRole(artistKey: String, role: String): List<CatalogTrackEntity>

    @Query(
        """
        SELECT * FROM catalog_tracks
        WHERE IFNULL(artist, '') LIKE '%' || :name || '%' COLLATE NOCASE
           OR IFNULL(albumArtist, '') LIKE '%' || :name || '%' COLLATE NOCASE
           OR IFNULL(title, '') LIKE '%' || :name || '%' COLLATE NOCASE
           OR IFNULL(album, '') LIKE '%' || :name || '%' COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getTracksMentioning(name: String, limit: Int = 8000): List<CatalogTrackEntity>

    @Query("DELETE FROM catalog_credits")
    suspend fun deleteAllCredits()

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

    @Query(
        """
        SELECT albumKey AS albumKey,
               MAX(album) AS name,
               MAX(COALESCE(albumArtist, artist)) AS artist,
               MAX(year) AS year,
               COUNT(DISTINCT lower(IFNULL(title, '')) || '|' ||
                     IFNULL(CAST(trackNumber AS TEXT), '') || '|' ||
                     IFNULL(CAST(discNumber AS TEXT), '1')) AS trackCount,
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
               COUNT(DISTINCT lower(IFNULL(title, '')) || '|' ||
                     IFNULL(CAST(trackNumber AS TEXT), '') || '|' ||
                     lower(IFNULL(album, ''))) AS trackCount,
               COUNT(DISTINCT albumKey) AS albumCount
        FROM catalog_tracks
        WHERE artistKey IS NOT NULL AND artistKey != ''
        GROUP BY artistKey
        """
    )
    suspend fun aggregateArtists(): List<ArtistAggregateRow>

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAlbums(): Flow<List<CatalogAlbumEntity>>

    @Query("SELECT * FROM catalog_albums ORDER BY artist COLLATE NOCASE, name COLLATE NOCASE")
    suspend fun getAllAlbums(): List<CatalogAlbumEntity>

    @Query("SELECT * FROM catalog_albums WHERE albumKey = :albumKey LIMIT 1")
    suspend fun getAlbum(albumKey: String): CatalogAlbumEntity?

    @Query("SELECT * FROM catalog_albums WHERE artistKey IN (:keys) ORDER BY year DESC, name COLLATE NOCASE")
    suspend fun getAlbumsForArtists(keys: List<String>): List<CatalogAlbumEntity>

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
            "(SELECT DISTINCT artistKey FROM catalog_tracks WHERE artistKey IS NOT NULL " +
            "UNION SELECT DISTINCT artistKey FROM catalog_credits WHERE artistKey IS NOT NULL)"
    )
    suspend fun deleteOrphanArtists()

    @Query("DELETE FROM catalog_credits WHERE subjectType = :subjectType AND subjectKey = :subjectKey")
    suspend fun deleteCredits(subjectType: String, subjectKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredits(credits: List<CatalogCreditEntity>)

    @Query("SELECT * FROM catalog_credits WHERE subjectType = :subjectType AND subjectKey = :subjectKey ORDER BY position")
    suspend fun creditsFor(subjectType: String, subjectKey: String): List<CatalogCreditEntity>

    @Query("SELECT * FROM artist_aliases")
    suspend fun getAllAliases(): List<ArtistAliasEntity>

    @Query("SELECT * FROM artist_aliases WHERE canonicalKey = :canonicalKey ORDER BY aliasName COLLATE NOCASE")
    suspend fun aliasesForCanonical(canonicalKey: String): List<ArtistAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(alias: ArtistAliasEntity)

    @Query("DELETE FROM artist_aliases WHERE aliasKey = :aliasKey")
    suspend fun deleteAlias(aliasKey: String)

    @Query("DELETE FROM artist_aliases WHERE canonicalKey = :canonicalKey OR aliasKey = :canonicalKey")
    suspend fun deleteAliasesInvolving(canonicalKey: String)

    @Query("UPDATE artist_aliases SET canonicalKey = :intoKey WHERE canonicalKey = :fromKey")
    suspend fun retargetAliases(fromKey: String, intoKey: String)

    @Query("UPDATE catalog_tracks SET artistKey = :intoKey WHERE artistKey = :fromKey")
    suspend fun retargetTrackArtistKey(fromKey: String, intoKey: String)

    @Query("UPDATE catalog_credits SET artistKey = :intoKey WHERE artistKey = :fromKey")
    suspend fun retargetCreditArtistKey(fromKey: String, intoKey: String)

    @Query("UPDATE catalog_albums SET artistKey = :intoKey WHERE artistKey = :fromKey")
    suspend fun retargetAlbumArtistKey(fromKey: String, intoKey: String)

    @Query(
        "SELECT * FROM catalog_artists WHERE mbid IS NOT NULL AND mbid != '' " +
            "ORDER BY mbid, trackCount DESC"
    )
    suspend fun artistsWithMbid(): List<CatalogArtistEntity>
}
