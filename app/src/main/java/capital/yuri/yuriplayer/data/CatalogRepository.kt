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
import java.io.File

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
        expandAlbumTracksLocked(albumKey)
    }

    suspend fun tracksForArtist(artistKey: String): List<Song> = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext emptyList()
        dedupeLogicalTracks(dao.getTracksForArtist(artistKey).map { it.toSong() })
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

    /** Delegate to shared expand that matches by album *name* across fragmented keys. */
    private suspend fun expandAlbumTracksLocked(albumKey: String): List<Song> =
        expandAlbumTracks(dao, albumKey)

    suspend fun artistItemForKey(artistKey: String): ArtistItem? = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext null
        val row = dao.getArtist(artistKey)
        val tracks = dedupeLogicalTracks(dao.getTracksForArtist(artistKey).map { it.toSong() })
        if (row == null && tracks.isEmpty()) return@withContext null
        val albums = tracks.mapNotNull { TrackIdentity.normalizeToken(it.album).takeIf { n -> n.isNotEmpty() } }.toSet()
        ArtistItem(
            name = row?.displayName ?: tracks.firstOrNull()?.effectiveAlbumArtist,
            trackCount = if (tracks.isNotEmpty()) tracks.size else (row?.trackCount ?: 0),
            albumCount = if (albums.isNotEmpty()) albums.size else (row?.albumCount ?: 0),
            songs = tracks
        )
    }

    suspend fun albumItemsForArtist(artistKey: String): List<AlbumItem> = withContext(Dispatchers.IO) {
        if (artistKey.isBlank()) return@withContext emptyList()
        val albumRows = dao.getAlbumsForArtist(artistKey)
        val raw = if (albumRows.isNotEmpty()) {
            albumRows.mapNotNull { row -> albumItemForKeyLocked(row.albumKey) }
        } else {
            dao.getTracksForArtist(artistKey)
                .map { it.toSong() }
                .groupBy { albumKey(it.album, it.effectiveAlbumArtist) }
                .map { (_, tracks) ->
                    val sorted = dedupeLogicalTracks(tracks)
                    AlbumItem(
                        name = sorted.firstOrNull()?.album,
                        artist = sorted.firstOrNull()?.effectiveAlbumArtist,
                        trackCount = sorted.size,
                        songs = sorted
                    )
                }
        }
        raw
            .groupBy { albumKey(it.name, it.artist) }
            .map { (_, group) ->
                if (group.size == 1) group.first()
                else {
                    val mergedSongs = dedupeLogicalTracks(group.flatMap { it.songs })
                    val bestName = group.mapNotNull { it.name }.maxByOrNull { it.length }
                    val bestArtist = group.mapNotNull { it.artist }.maxByOrNull { it.length }
                    AlbumItem(
                        name = bestName,
                        artist = bestArtist,
                        trackCount = mergedSongs.size,
                        songs = mergedSongs
                    )
                }
            }
            .sortedWith(
                compareByDescending<AlbumItem> {
                    it.songs.mapNotNull { s -> s.year }.maxOrNull() ?: Int.MIN_VALUE
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            )
    }

    suspend fun coverCandidatesForAlbum(albumKey: String, tracks: List<Song> = emptyList()): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            val songs = tracks.ifEmpty { expandAlbumTracksLocked(albumKey) }
            val row = dao.getAlbum(albumKey)
            val out = LinkedHashMap<String, CoverCandidate>()

            fun add(c: CoverCandidate) {
                out.putIfAbsent(c.id, c)
            }

            songs.filter { sourceTypeForSong(it) == SourceType.LOCAL }.forEach { song ->
                val path = song.path
                if (!path.isNullOrBlank() && !path.contains("://")) {
                    add(
                        CoverCandidate(
                            id = "local:${song.songKey}",
                            label = "Local file",
                            uri = song.albumArtUri?.toString() ?: "file://$path",
                            seedSong = song,
                            isLocal = true
                        )
                    )
                    val parent = File(path).parentFile
                    if (parent != null) {
                        for (name in listOf("cover.jpg", "cover.png", "folder.jpg", "AlbumArt.jpg")) {
                            val f = File(parent, name)
                            if (f.isFile && f.length() > 0) {
                                add(
                                    CoverCandidate(
                                        id = "folder:${f.absolutePath}",
                                        label = "Folder cover",
                                        uri = "file://${f.absolutePath}",
                                        seedSong = song,
                                        isLocal = true
                                    )
                                )
                                break
                            }
                        }
                    }
                }
            }

            row?.coverPath?.takeIf { it.isNotBlank() }?.let { path ->
                val uri = if (path.startsWith("/") || path.startsWith("file:")) {
                    if (path.startsWith("file:")) path else "file://$path"
                } else path
                add(CoverCandidate(id = "enriched:$path", label = "Saved cover", uri = uri, isLocal = true))
            }

            songs.forEach { song ->
                val uri = song.albumArtUri?.toString() ?: return@forEach
                if (!uri.startsWith("http", ignoreCase = true)) return@forEach
                val type = sourceTypeForSong(song)
                add(
                    CoverCandidate(
                        id = "remote:${type.name}:$uri",
                        label = "${friendlySourceName(type.name)} cover",
                        uri = uri,
                        seedSong = song,
                        isLocal = false
                    )
                )
            }

            row?.coverUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
                add(CoverCandidate(id = "catalog:$url", label = "Catalog cover", uri = url, isLocal = false))
            }

            out.values.sortedBy { if (it.isLocal) 0 else 1 }
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

    suspend fun offeringsMatchingSong(song: Song, limit: Int = 12): List<SourceOffering> =
        withContext(Dispatchers.IO) {
            val title = song.title?.trim().orEmpty()
            val artist = (song.effectiveAlbumArtist ?: song.artist)?.trim().orEmpty()
            val album = song.album?.trim().orEmpty()

            val exact = if (title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
                dao.findTracksMatching(title, artist, album, limit)
            } else emptyList()

            val candidates = LinkedHashMap<String, CatalogTrackEntity>()
            exact.forEach { candidates[it.songKey] = it }

            if (candidates.size < 2 && (title.isNotEmpty() || album.isNotEmpty())) {
                val needle = TrackIdentity.normalizeTitle(title).ifEmpty { title }
                val broaden = if (needle.isNotEmpty()) dao.searchTracks(needle.take(48), limit = 48) else emptyList()
                for (row in broaden) {
                    if (candidates.size >= limit) break
                    if (TrackIdentity.matches(song, row.toSong())) candidates.putIfAbsent(row.songKey, row)
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
        Log.i(TAG, "rollups rebuilt (SQL): albums=${albums.size} artists=${artists.size}")
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
