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

    /** Ordered lookup for playlist resolution (local + remote keys). */
    suspend fun getSongsByKeys(keys: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyList()
        // Room IN has a practical bind limit; chunk large playlists
        keys.chunked(400).flatMap { chunk ->
            dao.getTracksByKeys(chunk).map { it.toSong() }
        }
    }

    /** SQL search — never walk an in-memory 24k list. */
    suspend fun searchSongs(query: String, limit: Int = 80): List<Song> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            dao.searchTracks(q, limit).map { it.toSong() }
        }

    suspend fun searchAlbumRows(query: String, limit: Int = 12): List<CatalogAlbumEntity> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            dao.searchAlbums(q, limit)
        }

    suspend fun searchArtistRows(query: String, limit: Int = 12): List<CatalogArtistEntity> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            dao.searchArtists(q, limit)
        }

    /**
     * Explore album hits from the full catalog (local + remote).
     * Songs are not loaded here — call [albumItemForKey] / [tracksForAlbum] on open.
     */
    suspend fun searchAlbumsAsItems(query: String, limit: Int = 12): List<AlbumItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            dao.searchAlbums(q, limit).map { row ->
                AlbumItem(
                    name = row.name,
                    artist = row.artist,
                    trackCount = row.trackCount,
                    songs = emptyList()
                )
            }
        }

    /** Explore artist hits from the full catalog (local + remote). */
    suspend fun searchArtistsAsItems(query: String, limit: Int = 12): List<ArtistItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            dao.searchArtists(q, limit).map { row ->
                ArtistItem(
                    name = row.displayName,
                    trackCount = row.trackCount,
                    albumCount = row.albumCount,
                    songs = emptyList()
                )
            }
        }

    suspend fun tracksForAlbum(albumKey: String): List<Song> = withContext(Dispatchers.IO) {
        if (albumKey.isBlank()) return@withContext emptyList()
        dao.getTracksForAlbum(albumKey).map { it.toSong() }
    }

    suspend fun tracksForArtist(artistKey: String): List<Song> = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext emptyList()
        dao.getTracksForArtist(artistKey).map { it.toSong() }
    }

    /**
     * Full [AlbumItem] for detail / play — prefers catalog tracks for the key,
     * falls back to empty if the album row or tracks are missing.
     */
    suspend fun albumItemForKey(albumKey: String): AlbumItem? = withContext(Dispatchers.IO) {
        if (albumKey.isBlank()) return@withContext null
        val row = dao.getAlbum(albumKey)
        val tracks = dao.getTracksForAlbum(albumKey).map { it.toSong() }
            .sortedWith(
                compareBy<Song> { it.discNumber ?: 1 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            )
        if (row == null && tracks.isEmpty()) return@withContext null
        AlbumItem(
            name = row?.name ?: tracks.firstOrNull()?.album,
            artist = row?.artist ?: tracks.firstOrNull()?.effectiveAlbumArtist,
            trackCount = if (tracks.isNotEmpty()) tracks.size else (row?.trackCount ?: 0),
            songs = tracks
        )
    }

    /** Full [ArtistItem] + discography albums for detail. */
    suspend fun artistItemForKey(artistKey: String): ArtistItem? = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext null
        val row = dao.getArtist(artistKey)
        val tracks = dao.getTracksForArtist(artistKey).map { it.toSong() }
        if (row == null && tracks.isEmpty()) return@withContext null
        val albums = tracks.mapNotNull { normalizeAlbumName(it.album) }.toSet()
        ArtistItem(
            name = row?.displayName ?: tracks.firstOrNull()?.effectiveAlbumArtist,
            trackCount = if (tracks.isNotEmpty()) tracks.size else (row?.trackCount ?: 0),
            albumCount = if (albums.isNotEmpty()) albums.size else (row?.albumCount ?: 0),
            songs = tracks
        )
    }

    /** Albums for an artist key (catalog rollups + tracks loaded per album). */
    suspend fun albumItemsForArtist(artistKey: String): List<AlbumItem> = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext emptyList()
        val albumRows = dao.getAlbumsForArtist(artistKey)
        if (albumRows.isNotEmpty()) {
            albumRows.map { row ->
                val tracks = dao.getTracksForAlbum(row.albumKey).map { it.toSong() }
                    .sortedWith(
                        compareBy<Song> { it.discNumber ?: 1 }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                    )
                AlbumItem(
                    name = row.name,
                    artist = row.artist,
                    trackCount = if (tracks.isNotEmpty()) tracks.size else row.trackCount,
                    songs = tracks
                )
            }
        } else {
            // No rollup rows yet — group tracks directly
            dao.getTracksForArtist(artistKey)
                .map { it.toSong() }
                .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
                .map { (_, tracks) ->
                    val sorted = tracks.sortedWith(
                        compareBy<Song> { it.discNumber ?: 1 }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                    )
                    AlbumItem(
                        name = sorted.firstOrNull()?.album,
                        artist = sorted.firstOrNull()?.effectiveAlbumArtist,
                        trackCount = sorted.size,
                        songs = sorted
                    )
                }
                .sortedWith(
                    compareByDescending<AlbumItem> {
                        it.songs.mapNotNull { s -> s.year }.maxOrNull() ?: Int.MIN_VALUE
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                )
        }
    }

    suspend fun countRemoteTracks(): Int = withContext(Dispatchers.IO) {
        dao.countNonLocalTracks()
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

    /**
     * Resolve playable source offerings for one logical track (title+artist+album).
     * Bounded SQL only — used by song rows for multi-source badge + Sources sheet.
     */
    suspend fun offeringsMatchingSong(song: Song, limit: Int = 12): List<SourceOffering> =
        withContext(Dispatchers.IO) {
            val title = song.title?.trim().orEmpty()
            val artist = (song.effectiveAlbumArtist ?: song.artist)?.trim().orEmpty()
            val album = song.album?.trim().orEmpty()
            val rows = if (title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
                dao.findTracksMatching(title, artist, album, limit)
            } else {
                emptyList()
            }
            val fromDb = rows.map { row ->
                SourceOffering(
                    sourceType = SourceType.from(row.sourceType),
                    sourceId = row.sourceInstanceId,
                    sourceName = friendlySourceName(row.sourceType),
                    song = row.toSong()
                )
            }.distinctBy { "${it.sourceType.name}:${it.sourceId}:${it.song.songKey}" }

            if (fromDb.isNotEmpty()) return@withContext fromDb

            // Fallback: the song itself as a single offering
            listOf(
                SourceOffering(
                    sourceType = sourceTypeForSong(song),
                    sourceId = null,
                    sourceName = friendlySourceName(sourceTypeForSong(song).name),
                    song = song
                )
            )
        }

    suspend fun syncLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = musicRepository.scanLibrary()
        val seenAt = System.currentTimeMillis()
        val trackEntities = scanned.map { it.toLocalTrackEntity(seenAt) }

        dao.upsertTracks(trackEntities)
        dao.pruneStaleTracks(CatalogSources.LOCAL, null, seenAt)
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

    /**
     * Ensure playlist / My Stuff targets exist as catalog rows so they resolve
     * after restart. No-ops for keys already present.
     */
    suspend fun ensureTracksPresent(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        // One IN query instead of N individual getTrack calls
        val keys = songs.map { it.songKey }.distinct()
        val existing = keys.chunked(400).flatMap { dao.getTracksByKeys(it) }
            .map { it.songKey }.toHashSet()
        val missing = songs.filter { it.songKey !in existing }
        if (missing.isEmpty()) return@withContext
        val entities = missing.map { song ->
            val type = when {
                song.path?.startsWith("jellyfin:", true) == true -> CatalogSources.JELLYFIN
                song.path?.startsWith("subsonic:", true) == true -> CatalogSources.SUBSONIC
                song.path?.startsWith("navidrome:", true) == true -> CatalogSources.NAVIDROME
                else -> CatalogSources.LOCAL
            }
            song.toTrackEntity(
                sourceType = type,
                sourceInstanceId = null,
                externalId = song.path ?: song.contentUri.toString(),
                seenAt = now
            )
        }
        dao.upsertTracks(entities)
        Log.i(TAG, "ensureTracksPresent: upserted ${entities.size}")
    }

    suspend fun rebuildRollups() = withContext(Dispatchers.IO) {
        rebuildRollupsLocked()
    }

    /**
     * Album/artist index rebuild **without** loading the full track table.
     * Aggregates run in SQLite; we only merge prior cover/bio metadata from the
     * (much smaller) album/artist tables.
     */
    private suspend fun rebuildRollupsLocked() {
        val prevAlbums = dao.getAllAlbums().associateBy { it.albumKey }
        val prevArtists = dao.getAllArtists().associateBy { it.artistKey }

        val albumRows = dao.aggregateAlbums()
        val albums = albumRows.map { row ->
            val existing = prevAlbums[row.albumKey]
            val aKey = artistKey(row.artist)
            CatalogAlbumEntity(
                albumKey = row.albumKey,
                name = row.name,
                artist = row.artist,
                artistKey = aKey,
                year = row.year,
                trackCount = row.trackCount,
                releaseType = guessReleaseType(row.trackCount).name,
                mbid = existing?.mbid,
                coverPath = existing?.coverPath,
                coverUrl = row.coverUrl ?: existing?.coverUrl,
                primarySourceType = row.primarySourceType.ifBlank { CatalogSources.LOCAL },
                updatedAtMs = System.currentTimeMillis()
            )
        }
        dao.upsertAlbums(albums)

        val artistRows = dao.aggregateArtists()
        val artists = artistRows.map { row ->
            val existing = prevArtists[row.artistKey]
            CatalogArtistEntity(
                artistKey = row.artistKey,
                displayName = row.displayName.ifBlank { row.artistKey },
                trackCount = row.trackCount,
                albumCount = row.albumCount,
                bio = existing?.bio,
                imageUri = existing?.imageUri,
                websiteUrl = existing?.websiteUrl,
                linksJson = existing?.linksJson,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        dao.upsertArtists(artists)

        dao.deleteOrphanAlbums()
        dao.deleteOrphanArtists()
        Log.i(
            TAG,
            "rollups rebuilt (SQL): albums=${albums.size} artists=${artists.size}"
        )
    }

    suspend fun pruneRemoteSource(
        sourceType: String,
        sourceInstanceId: Long?,
        beforeMs: Long,
        keepSongKeys: Set<String>
    ) = withContext(Dispatchers.IO) {
        val deleted = if (keepSongKeys.isEmpty()) {
            dao.pruneStaleTracks(sourceType, sourceInstanceId, beforeMs)
        } else {
            // Chunk keep-keys — SQLite bind limits; rare path (end of sync only)
            val keep = keepSongKeys.toList()
            if (keep.size <= 400) {
                dao.pruneStaleTracksExcept(sourceType, sourceInstanceId, beforeMs, keep)
            } else {
                // Fallback: only load keys for this source that are candidates
                val stale = dao.getTracksBySource(sourceType)
                    .asSequence()
                    .filter {
                        (sourceInstanceId == null || it.sourceInstanceId == sourceInstanceId) &&
                            it.lastSeenAtMs < beforeMs &&
                            it.songKey !in keepSongKeys
                    }
                    .map { it.songKey }
                    .toList()
                stale.forEach { dao.deleteTrack(it) }
                stale.size
            }
        }
        if (deleted > 0) {
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            Log.i(
                TAG,
                "pruned $deleted stale $sourceType rows (kept ${keepSongKeys.size} My Stuff keys)"
            )
        }
    }

    /**
     * Load remote offerings for a **bounded** use (e.g. art pass). Prefer
     * [searchSongs] / [countRemoteTracks] for Explore UI.
     * LIMIT is applied in SQL — never materialize the whole remote library.
     */
    suspend fun getRemoteOfferings(limit: Int = 64): List<SourceOffering> =
        withContext(Dispatchers.IO) {
            val per = (limit.coerceAtLeast(1) / 3).coerceAtLeast(1)
            val jelly = dao.getTracksBySourceLimited(CatalogSources.JELLYFIN, per)
            val sub = dao.getTracksBySourceLimited(CatalogSources.SUBSONIC, per)
            val navi = dao.getTracksBySourceLimited(CatalogSources.NAVIDROME, per)
            (jelly + sub + navi)
                .asSequence()
                .take(limit)
                .map { row ->
                    SourceOffering(
                        sourceType = SourceType.from(row.sourceType),
                        sourceId = row.sourceInstanceId,
                        sourceName = friendlySourceName(row.sourceType),
                        song = row.toSong()
                    )
                }
                .toList()
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

    companion object {
        private const val TAG = "CatalogRepo"

        fun sourceTypeForSong(song: Song): SourceType {
            val p = song.path.orEmpty()
            return when {
                p.startsWith("jellyfin:", true) -> SourceType.JELLYFIN
                p.startsWith("navidrome:", true) -> SourceType.NAVIDROME
                p.startsWith("subsonic:", true) -> SourceType.SUBSONIC
                else -> SourceType.LOCAL
            }
        }

        fun friendlySourceName(raw: String): String =
            raw.lowercase().replaceFirstChar { it.titlecase() }

        fun isMultiSource(offerings: List<SourceOffering>): Boolean =
            offerings.map { "${it.sourceType.name}:${it.sourceId}" }.toSet().size > 1

        private fun normalizeAlbumName(value: String?): String? {
            if (value == null) return null
            val t = value.trim().replace(Regex("\\s+"), " ").lowercase()
            return t.takeIf { it.isNotEmpty() }
        }

        private fun guessReleaseType(trackCount: Int): ReleaseType = when {
            trackCount <= 3 -> ReleaseType.SINGLE
            trackCount <= 8 -> ReleaseType.EP
            else -> ReleaseType.ALBUM
        }
    }
}
