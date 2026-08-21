package capital.yuri.yuriplayer.data

import android.util.Log
import capital.yuri.yuriplayer.data.db.ArtistAliasEntity
import capital.yuri.yuriplayer.data.db.CatalogAlbumEntity
import capital.yuri.yuriplayer.data.db.CatalogArtistEntity
import capital.yuri.yuriplayer.data.db.CatalogCreditEntity
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

    suspend fun getLocalSongs(): List<Song> = withContext(Dispatchers.IO) {
        dao.getTracksBySource(CatalogSources.LOCAL).map { it.toSong() }
    }

    suspend fun getSongsByKeys(keys: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyList()
        keys.chunked(400).flatMap { chunk ->
            dao.getTracksByKeys(chunk).map { it.toSong() }
        }
    }

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

    suspend fun searchAlbumsAsItems(query: String, limit: Int = 12): List<AlbumItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val rows = dao.searchAlbums(q, limit)
            val seen = LinkedHashSet<String>()
            val out = ArrayList<AlbumItem>(rows.size)
            for (row in rows) {
                val item = albumItemForKeyLocked(row.albumKey) ?: continue
                val mergeKey = albumKey(item.name, item.artist)
                if (!seen.add(mergeKey)) continue
                out += item
            }
            out.take(limit)
        }

    suspend fun searchArtistsAsItems(query: String, limit: Int = 12): List<ArtistItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val aliasKeys = dao.getAllAliases().map { it.aliasKey }.toHashSet()
            val seen = LinkedHashSet<String>()
            val out = ArrayList<ArtistItem>()
            for (row in dao.searchArtists(q, limit * 3)) {
                if (row.artistKey in aliasKeys) continue
                if (ArtistAliasResolver.isAlias(row.artistKey)) continue
                if (isCombinedArtistName(row.displayName)) continue
                val name = primaryArtistName(row.displayName) ?: row.displayName
                val key = artistKey(name) ?: continue
                if (!seen.add(key)) continue
                out += ArtistItem(
                    name = name,
                    trackCount = row.trackCount,
                    albumCount = row.albumCount,
                    songs = emptyList()
                )
                if (out.size >= limit) break
            }
            out
        }

    suspend fun tracksForAlbum(albumKey: String): List<Song> = withContext(Dispatchers.IO) {
        if (albumKey.isBlank()) return@withContext emptyList()
        expandAlbumTracksLocked(albumKey)
    }

    suspend fun tracksForArtist(artistKey: String): List<Song> = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext emptyList()
        dedupeLogicalTracks(primaryTracksForArtistLocked(artistKey))
    }

    /** Releases this artist owns (primary). Local + remote copies merge by albumKey. */
    suspend fun albumItemsForArtist(artistKey: String, displayName: String? = null): List<AlbumItem> =
        lightAlbumItemsForArtist(dao, artistKey, displayName)

    /** Guest appearances on other artists' releases. */
    suspend fun appearsOnAlbumItems(artistKey: String, displayName: String? = null): List<AlbumItem> =
        withContext(Dispatchers.IO) {
            lightAppearsOnForArtist(dao, artistKey, displayName)
        }

    suspend fun albumItemForKey(albumKey: String): AlbumItem? = withContext(Dispatchers.IO) {
        albumItemForKeyLocked(albumKey)
    }

    private suspend fun albumItemForKeyLocked(albumKey: String): AlbumItem? {
        if (albumKey.isBlank()) return null
        val row = dao.getAlbum(albumKey)
        val tracks = expandAlbumTracksLocked(albumKey)
        if (row == null && tracks.isEmpty()) return null
        return AlbumItem(
            name = row?.name ?: tracks.firstOrNull()?.album,
            artist = row?.artist ?: tracks.firstOrNull()?.effectiveAlbumArtist,
            trackCount = if (tracks.isNotEmpty()) tracks.size else (row?.trackCount ?: 0),
            songs = tracks
        )
    }

    private suspend fun expandAlbumTracksLocked(albumKey: String): List<Song> =
        expandAlbumTracks(dao, albumKey)

    suspend fun artistItemForKey(artistKey: String, hintName: String? = null): ArtistItem? = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext null
        val key = ArtistAliasResolver.resolve(artistKey)
        val row = dao.getArtist(key)?.takeUnless { isCombinedArtistName(it.displayName) }
            ?: dao.getArtist(artistKey)?.takeUnless { isCombinedArtistName(it.displayName) }
        val tracks = dedupeLogicalTracks(primaryTracksForArtistLocked(key, hintName ?: row?.displayName))
        if (row == null && tracks.isEmpty()) return@withContext null
        val albums = tracks.mapNotNull { TrackIdentity.normalizeToken(it.album).takeIf { n -> n.isNotEmpty() } }.toSet()
        val name = primaryArtistName(row?.displayName)
            ?: row?.displayName
            ?: primaryArtistName(hintName)
            ?: hintName
            ?: primaryArtistName(tracks.firstOrNull()?.effectiveAlbumArtist)
            ?: tracks.firstOrNull()?.effectiveAlbumArtist
        ArtistItem(
            name = name,
            trackCount = if (tracks.isNotEmpty()) tracks.size else (row?.trackCount ?: 0),
            albumCount = if (albums.isNotEmpty()) albums.size else (row?.albumCount ?: 0),
            songs = tracks
        )
    }

    suspend fun coverCandidatesForAlbum(albumKey: String, tracks: List<Song> = emptyList()): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            val songs = tracks.ifEmpty { expandAlbumTracksLocked(albumKey) }
            val row = dao.getAlbum(albumKey)
            CoverCandidates.build(songs, row?.coverPath, row?.coverUrl)
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

    suspend fun songsForSource(sourceType: String, sourceInstanceId: Long?): List<Song> =
        withContext(Dispatchers.IO) {
            dao.getTracksForSourceInstance(sourceType, sourceInstanceId).map { it.toSong() }
        }

    suspend fun offeringsMatchingSong(song: Song, limit: Int = 12): List<SourceOffering> =
        withContext(Dispatchers.IO) {
            val title = song.title?.trim().orEmpty()
            val album = song.album?.trim().orEmpty()
            val candidates = LinkedHashMap<String, CatalogTrackEntity>()

            if (title.isNotEmpty() || album.isNotEmpty()) {
                dao.findTracksMatching(
                    title,
                    song.effectiveAlbumArtist?.trim().orEmpty(),
                    album,
                    limit
                ).forEach { candidates[it.songKey] = it }
            }

            val needle = TrackIdentity.normalizeTitle(title).ifEmpty { title }
            if (needle.isNotEmpty()) {
                dao.searchTracks(needle.take(48), limit = 80).forEach { row ->
                    if (candidates.size >= limit) return@forEach
                    if (TrackIdentity.matches(song, row.toSong()) ||
                        TrackIdentity.titlesMatch(song.title, row.title) &&
                        TrackIdentity.albumsMatch(song.album, row.album)
                    ) {
                        candidates.putIfAbsent(row.songKey, row)
                    }
                }
            }

            val fromDb = candidates.values
                .map { row ->
                    SourceOffering(
                        sourceType = SourceType.from(row.sourceType),
                        sourceId = row.sourceInstanceId,
                        sourceName = friendlySourceName(row.sourceType),
                        song = row.toSong()
                    )
                }
                .distinctBy { "${it.sourceType.name}:${it.sourceId}:${it.song.songKey}" }
                .sortedBy { it.sourceType.rank }
                .take(limit)

            if (fromDb.isNotEmpty()) return@withContext fromDb

            listOf(
                SourceOffering(
                    sourceType = sourceTypeForSong(song),
                    sourceId = null,
                    sourceName = friendlySourceName(sourceTypeForSong(song).name),
                    song = song
                )
            )
        }

    suspend fun patchTrackTags(
        songKey: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        year: Int? = null,
        genre: String? = null
    ) = withContext(Dispatchers.IO) {
        val row = dao.getTrack(songKey) ?: return@withContext
        val nextTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: row.title
        val nextArtist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: row.artist
        val nextAlbumArtist = albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: row.albumArtist
        val nextAlbum = album?.trim()?.takeIf { it.isNotEmpty() } ?: row.album
        val nextYear = year?.takeIf { it in 1000..2100 } ?: row.year
        val effectiveArtist = nextAlbumArtist ?: nextArtist
        val nextAlbumKey = if (nextAlbum != null) albumKey(nextAlbum, effectiveArtist) else row.albumKey
        val nextArtistKey = artistKey(effectiveArtist) ?: row.artistKey
        @Suppress("UNUSED_VARIABLE")
        val ignoredGenre = genre
        dao.upsertTrack(
            row.copy(
                title = nextTitle,
                artist = nextArtist,
                albumArtist = nextAlbumArtist,
                album = nextAlbum,
                year = nextYear,
                albumKey = nextAlbumKey,
                artistKey = nextArtistKey,
                isTagged = true,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "patchTrackTags $songKey")
    }

    suspend fun syncLocalLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = musicRepository.scanLibrary()
        val seenAt = System.currentTimeMillis()
        val trackEntities = scanned.map { it.toLocalTrackEntity(seenAt) }

        dao.upsertTracks(trackEntities)
        persistCreditsLocked(scanned)
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
    ): IngestStats = withContext(Dispatchers.IO) {
        ingestRemoteDeltaLocked(songs, sourceType, sourceInstanceId, seenAt)
    }

    data class IngestStats(
        val inserted: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0
    ) {
        val changed: Int get() = inserted + updated
        val seen: Int get() = inserted + updated + unchanged
        operator fun plus(other: IngestStats) = IngestStats(
            inserted = inserted + other.inserted,
            updated = updated + other.updated,
            unchanged = unchanged + other.unchanged
        )
    }

    private suspend fun ingestRemoteDeltaLocked(
        songs: List<Song>,
        sourceType: String,
        sourceInstanceId: Long?,
        seenAt: Long
    ): IngestStats {
        if (songs.isEmpty()) return IngestStats()
        val incoming = songs.map { song ->
            song.toTrackEntity(
                sourceType = sourceType,
                sourceInstanceId = sourceInstanceId,
                externalId = song.path ?: song.contentUri.toString(),
                seenAt = seenAt
            )
        }
        val ids = incoming.mapNotNull { it.externalId }
        val existing = HashMap<String, CatalogTrackEntity>(ids.size)
        ids.chunked(400).forEach { chunk ->
            dao.tracksByExternalIds(sourceType, sourceInstanceId, chunk).forEach { row ->
                val key = row.externalId ?: return@forEach
                existing[key] = row
            }
        }
        val upsert = ArrayList<CatalogTrackEntity>()
        val patchSongs = ArrayList<Song>()
        val touch = ArrayList<String>()
        var inserted = 0
        var updated = 0
        incoming.forEachIndexed { i, ent ->
            val id = ent.externalId
            val prev = id?.let { existing[it] }
            when {
                prev == null -> {
                    upsert += ent
                    patchSongs += songs[i]
                    inserted++
                }
                prev.sameRemoteMeta(ent) -> {
                    if (id != null) touch += id
                }
                else -> {
                    upsert += ent.copy(id = prev.id, songKey = prev.songKey)
                    patchSongs += songs[i]
                    updated++
                }
            }
        }
        if (touch.isNotEmpty()) {
            touch.chunked(400).forEach { chunk ->
                dao.touchLastSeenByExternalIds(sourceType, sourceInstanceId, chunk, seenAt)
            }
        }
        if (upsert.isNotEmpty()) {
            dao.upsertTracks(upsert)
            persistCreditsLocked(patchSongs)
        }
        return IngestStats(
            inserted = inserted,
            updated = updated,
            unchanged = touch.size
        )
    }

    suspend fun externalIdsForSource(sourceType: String, sourceInstanceId: Long?): Set<String> =
        withContext(Dispatchers.IO) {
            dao.externalIdsForSource(sourceType, sourceInstanceId).toHashSet()
        }

    /**
     * Drop rows for this source whose [externalId] is not in [liveIds].
     * Incomplete scans must not call this.
     */
    suspend fun pruneMissingExternalIds(
        sourceType: String,
        sourceInstanceId: Long?,
        liveIds: Set<String>,
        keepSongKeys: Set<String>
    ): Int = withContext(Dispatchers.IO) {
        val existing = dao.externalIdsForSource(sourceType, sourceInstanceId)
        val gone = existing.filter { it !in liveIds }
        if (gone.isEmpty()) return@withContext 0
        var deleted = 0
        gone.chunked(400).forEach { chunk ->
            val keys = dao.songKeysForExternalIds(sourceType, sourceInstanceId, chunk)
                .filter { it !in keepSongKeys }
            if (keys.isEmpty()) return@forEach
            dao.deleteCreditsForTracks(keys)
            deleted += dao.deleteTracksByKeys(keys)
        }
        if (deleted > 0) {
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            Log.i(TAG, "pruned $deleted missing $sourceType rows (live=${liveIds.size})")
        }
        deleted
    }

    suspend fun ensureTracksPresent(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val keys = songs.map { it.songKey }.distinct()
        val existing = keys.chunked(400).flatMap { dao.getTracksByKeys(it) }.map { it.songKey }.toHashSet()
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

    private suspend fun rebuildRollupsLocked() {
        loadAliasesLocked()
        applyMbidAliasesLocked()
        normalizeTrackIdentityLocked()
        persistCreditsLocked(dao.getAllTracks().map { it.toSong() }, replaceAll = true)

        val prevAlbums = dao.getAllAlbums().associateBy { it.albumKey }
        val prevArtists = dao.getAllArtists().associateBy { it.artistKey }

        val albumRows = dao.aggregateAlbums()
        val albums = albumRows.map { row ->
            val existing = prevAlbums[row.albumKey]
            val aKey = artistKey(row.artist)
            CatalogAlbumEntity(
                albumKey = row.albumKey,
                name = row.name,
                artist = primaryArtistName(row.artist) ?: row.artist,
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
                displayName = existing?.displayName?.takeUnless { isCombinedArtistName(it) }
                    ?: primaryArtistName(row.displayName)
                    ?: row.displayName.ifBlank { row.artistKey },
                trackCount = row.trackCount,
                albumCount = row.albumCount,
                bio = existing?.bio,
                imageUri = existing?.imageUri,
                websiteUrl = existing?.websiteUrl,
                linksJson = existing?.linksJson,
                mbid = existing?.mbid,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        dao.upsertArtists(artists)

        dao.deleteOrphanAlbums()
        dao.deleteOrphanArtists()
        Log.i(TAG, "rollups rebuilt (SQL): albums=${albums.size} artists=${artists.size}")
    }

    suspend fun loadAliases() = withContext(Dispatchers.IO) {
        loadAliasesLocked()
    }

    private suspend fun loadAliasesLocked() {
        val rows = dao.getAllAliases()
        ArtistAliasResolver.replace(rows.associate { it.aliasKey to it.canonicalKey })
    }

    /**
     * Remember [fromName] as an alias of [intoName]. Track rows stay as-is;
     * lookups expand through [ArtistAliasResolver].
     */
    suspend fun mergeArtists(fromName: String, intoName: String): ArtistItem? =
        withContext(Dispatchers.IO) {
            val fromKey = rawArtistKey(fromName) ?: return@withContext null
            val intoKey = ArtistAliasResolver.resolve(rawArtistKey(intoName) ?: return@withContext null)
            if (fromKey == intoKey) return@withContext artistItemForKey(intoKey, intoName)
            dao.upsertAlias(
                ArtistAliasEntity(
                    aliasKey = fromKey,
                    canonicalKey = intoKey,
                    aliasName = fromName.trim(),
                    source = ArtistAliasEntity.SOURCE_USER
                )
            )
            dao.retargetAliases(fromKey, intoKey)
            loadAliasesLocked()
            artistItemForKey(intoKey, intoName)
        }

    suspend fun unmergeArtist(aliasKey: String) = withContext(Dispatchers.IO) {
        dao.deleteAlias(aliasKey)
        loadAliasesLocked()
    }

    suspend fun aliasesForArtist(artistKey: String): List<ArtistAliasEntity> =
        withContext(Dispatchers.IO) {
            val canonical = ArtistAliasResolver.resolve(artistKey)
            dao.aliasesForCanonical(canonical)
        }

    private suspend fun applyMbidAliasesLocked() {
        val groups = dao.artistsWithMbid().groupBy { it.mbid.orEmpty() }
        var changed = false
        for ((mbid, rows) in groups) {
            if (mbid.isBlank() || rows.size < 2) continue
            val canonical = rows.maxWith(
                compareBy<CatalogArtistEntity> { it.trackCount }.thenBy { it.displayName }
            )
            for (other in rows) {
                if (other.artistKey == canonical.artistKey) continue
                if (ArtistAliasResolver.resolve(other.artistKey) == canonical.artistKey) continue
                dao.upsertAlias(
                    ArtistAliasEntity(
                        aliasKey = other.artistKey,
                        canonicalKey = canonical.artistKey,
                        aliasName = other.displayName,
                        source = ArtistAliasEntity.SOURCE_MBID
                    )
                )
                changed = true
            }
        }
        if (changed) loadAliasesLocked()
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
            val keep = keepSongKeys.toList()
            if (keep.size <= 400) {
                dao.pruneStaleTracksExcept(sourceType, sourceInstanceId, beforeMs, keep)
            } else {
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
            Log.i(TAG, "pruned $deleted stale $sourceType rows (kept ${keepSongKeys.size} My Stuff keys)")
        }
    }

    suspend fun getRemoteOfferings(limit: Int = 64): List<SourceOffering> =
        withContext(Dispatchers.IO) {
            val per = (limit.coerceAtLeast(1) / 3).coerceAtLeast(1)
            val jelly = dao.getTracksBySourceLimited(CatalogSources.JELLYFIN, per)
            val sub = dao.getTracksBySourceLimited(CatalogSources.SUBSONIC, per)
            val navi = dao.getTracksBySourceLimited(CatalogSources.NAVIDROME, per)
            (jelly + sub + navi).asSequence().take(limit).map { row ->
                SourceOffering(
                    sourceType = SourceType.from(row.sourceType),
                    sourceId = row.sourceInstanceId,
                    sourceName = friendlySourceName(row.sourceType),
                    song = row.toSong()
                )
            }.toList()
        }

    suspend fun importToMyStuff(song: Song, sourceType: String, sourceInstanceId: Long?, externalId: String?) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.upsertTrack(
                song.toTrackEntity(
                    sourceType = sourceType,
                    sourceInstanceId = sourceInstanceId,
                    externalId = externalId,
                    seenAt = now
                )
            )
            Log.i(TAG, "imported to My Stuff: ${song.songKey} from $sourceType")
        }

    suspend fun applyAlbumYear(albumKey: String, year: Int) = withContext(Dispatchers.IO) {
        if (year !in 1000..2100) return@withContext
        val album = dao.getAlbum(albumKey) ?: return@withContext
        dao.upsertAlbum(album.copy(year = year, updatedAtMs = System.currentTimeMillis()))
        val tracks = expandAlbumTracksLocked(albumKey).mapNotNull { song ->
            dao.getTrack(song.songKey)?.let { t ->
                if (t.year == null || t.year <= 0) t.copy(year = year) else t
            }
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
        dao.upsertArtist(artist.copy(imageUri = imageUri, updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun coverPathForAlbum(albumKey: String): String? =
        dao.getAlbum(albumKey)?.coverPath

    private suspend fun persistCreditsLocked(songs: List<Song>, replaceAll: Boolean = false) {
        if (songs.isEmpty()) return
        if (replaceAll) dao.deleteAllCredits()
        val credits = ArrayList<CatalogCreditEntity>()
        val albumSeen = HashSet<String>()
        val artistStubs = LinkedHashMap<String, CatalogArtistEntity>()
        val existingArtists = dao.getAllArtists().associateBy { it.artistKey }.toMutableMap()
        for (song in songs) {
            if (!replaceAll) dao.deleteCredits("TRACK", song.songKey)
            allCreditsForSong(song).forEach { c ->
                val key = artistKey(c.name) ?: return@forEach
                credits += CatalogCreditEntity(
                    subjectType = "TRACK",
                    subjectKey = song.songKey,
                    artistKey = key,
                    displayName = c.name,
                    role = c.role.name,
                    position = c.position
                )
                if (key !in existingArtists && key !in artistStubs) {
                    artistStubs[key] = CatalogArtistEntity(
                        artistKey = key,
                        displayName = c.name,
                        trackCount = 0,
                        albumCount = 0,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            }
            val aKey = albumKey(song.album, song.effectiveAlbumArtist)
            if (aKey.isNotBlank() && albumSeen.add(aKey)) {
                if (!replaceAll) dao.deleteCredits("ALBUM", aKey)
                parseArtistCreditList(song.albumArtist ?: song.effectiveAlbumArtist).forEach { c ->
                    val key = artistKey(c.name) ?: return@forEach
                    credits += CatalogCreditEntity(
                        subjectType = "ALBUM",
                        subjectKey = aKey,
                        artistKey = key,
                        displayName = c.name,
                        role = c.role.name,
                        position = c.position
                    )
                }
            }
            song.musicBrainzArtistId?.takeIf { it.isNotBlank() }?.let { mbid ->
                listOfNotNull(
                    artistKey(song.effectiveAlbumArtist),
                    artistKey(song.artist)
                ).distinct().forEach { key ->
                    val existing = existingArtists[key] ?: dao.getArtist(key)
                    if (existing != null && existing.mbid.isNullOrBlank()) {
                        val next = existing.copy(mbid = mbid, updatedAtMs = System.currentTimeMillis())
                        dao.upsertArtist(next)
                        existingArtists[key] = next
                    }
                }
            }
        }
        if (artistStubs.isNotEmpty()) dao.upsertArtists(artistStubs.values.toList())
        if (credits.isNotEmpty()) credits.chunked(500).forEach { dao.upsertCredits(it) }
    }

    private suspend fun normalizeTrackIdentityLocked() {
        val tracks = dao.getAllTracks()
        val changed = ArrayList<CatalogTrackEntity>()
        for (row in tracks) {
            val song = row.toSong()
            val newAlbum = albumKey(song.album, song.effectiveAlbumArtist).takeIf { !song.album.isNullOrBlank() }
            val newArtist = artistKey(song.effectiveAlbumArtist)
            if (row.albumKey != newAlbum || row.artistKey != newArtist) {
                changed += row.copy(albumKey = newAlbum, artistKey = newArtist)
            }
        }
        val byKey = HashMap<String, CatalogTrackEntity>(tracks.size)
        tracks.forEach { byKey[it.songKey] = it }
        changed.forEach { byKey[it.songKey] = it }
        coalesceSplitAlbums(byKey.values.toList()).forEach { row ->
            val prev = byKey[row.songKey]
            if (prev == null || prev != row) {
                byKey[row.songKey] = row
                val idx = changed.indexOfFirst { it.songKey == row.songKey }
                if (idx >= 0) changed[idx] = row else changed += row
            }
        }
        if (changed.isEmpty()) return
        changed.chunked(400).forEach { dao.upsertTracks(it) }
        Log.i(TAG, "normalized identity on ${changed.size} tracks")
    }

    /**
     * Same remote library + same album title + unique disc/track slots =
     * one release (Navidrome VA / split-artist albums). Do not merge when
     * two albums share a title and collide on track numbers (Greatest Hits).
     */
    private fun coalesceSplitAlbums(tracks: List<CatalogTrackEntity>): List<CatalogTrackEntity> {
        val out = ArrayList<CatalogTrackEntity>()
        val groups = tracks
            .filter { !it.album.isNullOrBlank() && it.sourceType != CatalogSources.LOCAL }
            .groupBy { Triple(it.sourceType, it.sourceInstanceId, foldTagToken(it.album.orEmpty())) }
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val keys = group.mapNotNull { it.albumKey }.toHashSet()
            if (keys.size <= 1) continue
            if (hasCollidingTrackSlots(group)) continue
            val canonicalArtist = pickCanonicalAlbumArtist(group) ?: continue
            val canonicalKey = albumKey(group.first().album, canonicalArtist)
            val canonicalArtistKey = artistKey(canonicalArtist)
            for (row in group) {
                val stamp = row.albumArtist.isNullOrBlank() ||
                    row.albumArtist.equals(row.artist, ignoreCase = true)
                val next = row.copy(
                    albumArtist = if (stamp) canonicalArtist else row.albumArtist,
                    albumKey = canonicalKey,
                    artistKey = canonicalArtistKey
                )
                if (next != row) out += next
            }
        }
        return out
    }

    private fun hasCollidingTrackSlots(group: List<CatalogTrackEntity>): Boolean {
        data class Slot(val disc: Int, val track: Int)
        val titles = HashMap<Slot, String>()
        for (t in group) {
            val tn = t.trackNumber ?: continue
            val slot = Slot(t.discNumber ?: 1, tn)
            val title = TrackIdentity.normalizeTitle(t.title)
            if (title.isEmpty()) continue
            val existing = titles[slot]
            if (existing != null && existing != title) return true
            titles[slot] = title
        }
        return false
    }

    private fun pickCanonicalAlbumArtist(group: List<CatalogTrackEntity>): String? {
        val tagged = group.mapNotNull { row ->
            val aa = row.albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ta = row.artist?.trim().orEmpty()
            if (aa.equals(ta, ignoreCase = true)) null else primaryArtistName(aa) ?: aa
        }
        val pool = if (tagged.isNotEmpty()) {
            tagged
        } else {
            group.mapNotNull {
                primaryArtistName(it.albumArtist) ?: it.albumArtist?.trim()?.takeIf { n -> n.isNotEmpty() }
                    ?: primaryArtistName(it.artist) ?: it.artist?.trim()?.takeIf { n -> n.isNotEmpty() }
            }
        }
        if (pool.isEmpty()) return null
        return pool.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .first()
            .key
    }

    private suspend fun primaryTracksForArtistLocked(artistKey: String, hintName: String? = null): List<Song> {
        val keys = ArtistAliasResolver.identityKeys(artistKey)
        val out = LinkedHashMap<String, Song>()
        dao.getTracksForArtists(keys).map { it.toSong() }.forEach { out[it.songKey] = it }
        dao.getTracksByCreditRoles(keys, ArtistRole.PRIMARY.name)
            .map { it.toSong() }
            .forEach { out.putIfAbsent(it.songKey, it) }
        val names = buildList {
            dao.getArtist(keys.first())?.displayName?.let { add(it) }
            hintName?.let { add(it) }
            dao.aliasesForCanonical(keys.first()).forEach { add(it.aliasName) }
        }.distinct()
        names.filter { it.length >= 3 }.forEach { name ->
            dao.getTracksMentioning(name).forEach { entity ->
                val song = entity.toSong()
                val hit = allCreditsForSong(song).any {
                    it.role == ArtistRole.PRIMARY &&
                        ArtistAliasResolver.resolve(artistKey(it.name) ?: "") ==
                        ArtistAliasResolver.resolve(artistKey)
                }
                if (hit) out.putIfAbsent(entity.songKey, song)
            }
        }
        return out.values.toList()
    }

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

    private fun CatalogTrackEntity.sameRemoteMeta(incoming: CatalogTrackEntity): Boolean {
        if (!incoming.title.isNullOrBlank() && title != incoming.title) return false
        if (!incoming.artist.isNullOrBlank() && artist != incoming.artist) return false
        if (!incoming.albumArtist.isNullOrBlank() && albumArtist != incoming.albumArtist) return false
        if (!incoming.album.isNullOrBlank() && album != incoming.album) return false
        if (incoming.year != null && year != incoming.year) return false
        if (incoming.trackNumber != null && trackNumber != incoming.trackNumber) return false
        if (incoming.discNumber != null && discNumber != incoming.discNumber) return false
        if (incoming.durationMs != null && incoming.durationMs > 0 && durationMs != incoming.durationMs) return false
        if (incoming.albumArtUri != null && albumArtUri != incoming.albumArtUri) return false
        if (incoming.mimeType != null && mimeType != incoming.mimeType) return false
        return true
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

        fun dedupeLogicalTracks(tracks: List<Song>): List<Song> {
            if (tracks.isEmpty()) return emptyList()
            return tracks
                .groupBy { TrackIdentity.of(it) }
                .values
                .map { group ->
                    val preferred = group.minByOrNull { sourceTypeForSong(it).rank } ?: group.first()
                    TrackIdentity.withRichestDisplay(preferred, group)
                }
                .sortedWith(
                    compareBy<Song> { it.discNumber ?: 1 }
                        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                )
        }

        private fun guessReleaseType(trackCount: Int): ReleaseType = when {
            trackCount <= 3 -> ReleaseType.SINGLE
            trackCount <= 8 -> ReleaseType.EP
            else -> ReleaseType.ALBUM
        }
    }
}
