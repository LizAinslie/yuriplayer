package capital.yuri.yuriplayer.data

import android.net.Uri
import capital.yuri.yuriplayer.data.db.PlaylistCoverEntity
import capital.yuri.yuriplayer.data.db.PlaylistDao
import capital.yuri.yuriplayer.data.db.PlaylistEntity
import capital.yuri.yuriplayer.data.db.PlaylistTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val customImageUri: String? = null,
    val activeCoverId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val trackCount: Int = 0,
    val songs: List<Song> = emptyList()
)

/** One stored custom cover (public or secret). */
data class PlaylistCoverSlot(
    val id: String,
    val uri: String,
    val isSecret: Boolean,
    val sortOrder: Int,
    val isActive: Boolean
)

/**
 * Cover strategy (Spotify-ish):
 * 1. Active custom cover (or legacy [Playlist.customImageUri])
 * 2. else first 4 *unique* album arts → collage
 * 3. else single unique art
 * 4. else empty (placeholder in UI)
 */
data class PlaylistCover(
    val customUri: Uri? = null,
    val artUris: List<Uri> = emptyList()
) {
    val mode: CoverMode
        get() = when {
            customUri != null -> CoverMode.CUSTOM
            artUris.size >= 4 -> CoverMode.COLLAGE
            artUris.isNotEmpty() -> CoverMode.SINGLE
            else -> CoverMode.EMPTY
        }

    enum class CoverMode { CUSTOM, COLLAGE, SINGLE, EMPTY }
}

/**
 * Playlists resolve tracks from the **catalog** (local + remote), never by
 * scanning [LibraryIndex] on the collector thread.
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val catalog: CatalogRepository,
    private val images: UserImageStore
) {

    fun observePlaylists(): Flow<List<Playlist>> =
        combine(dao.observeAll(), dao.observeTrackCounts()) { entities, counts ->
            val countMap = counts.associate { it.playlistId to it.trackCount }
            entities.map { e -> e.toPlaylist(countMap[e.id] ?: 0, emptyList()) }
        }.flowOn(Dispatchers.Default)

    fun observePlaylistsResolved(): Flow<List<Playlist>> = observePlaylists()

    fun observePlaylist(id: String): Flow<Playlist?> =
        combine(dao.observe(id), dao.observeTracks(id)) { entity, tracks ->
            entity to tracks
        }.map { (entity, tracks) ->
            if (entity == null) return@map null
            val songs = resolveSongs(tracks)
            entity.toPlaylist(tracks.size, songs)
        }.flowOn(Dispatchers.IO)

    fun observeCovers(playlistId: String): Flow<List<PlaylistCoverSlot>> =
        combine(dao.observe(playlistId), dao.observeCovers(playlistId)) { entity, covers ->
            val active = entity?.activeCoverId
            covers.map { c ->
                PlaylistCoverSlot(
                    id = c.id,
                    uri = c.uri,
                    isSecret = c.isSecret,
                    sortOrder = c.sortOrder,
                    isActive = c.id == active ||
                        (active == null && c.uri == entity?.customImageUri)
                )
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun resolveSongs(tracks: List<PlaylistTrackEntity>): List<Song> {
        if (tracks.isEmpty()) return emptyList()
        val keys = tracks.map { it.songKey }
        val byKey = catalog.getSongsByKeys(keys).associateBy { it.songKey }
        return tracks.mapNotNull { byKey[it.songKey] }
    }

    suspend fun get(id: String): Playlist? = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext null
        val tracks = dao.getTracks(id)
        entity.toPlaylist(tracks.size, resolveSongs(tracks))
    }

    /**
     * Idempotent import for a remote playlist. [id] is the remote stable id
     * (`JELLYFIN:1:uuid`). Existing rows keep their cover; tracks are replaced
     * from the source of truth on first insert only.
     */
    suspend fun upsertImported(id: String, name: String, songs: List<Song>): Playlist =
        withContext(Dispatchers.IO) {
            val existing = dao.get(id)
            val now = System.currentTimeMillis()
            if (existing == null) {
                dao.upsert(
                    PlaylistEntity(
                        id = id,
                        name = name.trim().ifEmpty { "Playlist" },
                        createdAtMs = now,
                        updatedAtMs = now
                    )
                )
                catalog.ensureTracksPresent(songs)
                dao.replaceTracks(id, songs.map { it.songKey })
            }
            val entity = dao.get(id)!!
            val tracks = dao.getTracks(id)
            entity.toPlaylist(tracks.size, resolveSongs(tracks))
        }

    suspend fun create(name: String, description: String? = null): Playlist =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = PlaylistEntity(
                id = id,
                name = name.trim().ifEmpty { "New playlist" },
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                createdAtMs = now,
                updatedAtMs = now
            )
            dao.upsert(entity)
            entity.toPlaylist(0, emptyList())
        }

    suspend fun rename(id: String, name: String, description: String? = null) =
        withContext(Dispatchers.IO) {
            val existing = dao.get(id) ?: return@withContext
            dao.upsert(
                existing.copy(
                    name = name.trim().ifEmpty { existing.name },
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }

    /**
     * Legacy single-cover API: adds (or replaces sole public) cover and makes it active.
     * Prefer [addCover] / [setActiveCover] for multi-cover flows.
     */
    suspend fun setCustomImage(id: String, uri: String?) =
        withContext(Dispatchers.IO) {
            val existing = dao.get(id) ?: return@withContext
            if (uri.isNullOrBlank()) {
                // Clear active public cover only — keep secret slots.
                val activeId = existing.activeCoverId
                if (activeId != null) {
                    val active = dao.getCover(activeId)
                    if (active != null && !active.isSecret) {
                        images.deleteSlot(UserImageStore.NS_PLAYLISTS, id, activeId)
                        dao.deleteCover(activeId)
                    }
                }
                dao.upsert(
                    existing.copy(
                        customImageUri = null,
                        activeCoverId = null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                return@withContext
            }
            addCoverInternal(existing, uri, isSecret = false, makeActive = true)
        }

    /** Add a new cover. [isSecret] covers are not auto-activated. */
    suspend fun addCover(
        playlistId: String,
        sourceUri: String,
        isSecret: Boolean = false,
        makeActive: Boolean = !isSecret
    ): PlaylistCoverSlot? = withContext(Dispatchers.IO) {
        val existing = dao.get(playlistId) ?: return@withContext null
        addCoverInternal(existing, sourceUri, isSecret, makeActive)
    }

    private suspend fun addCoverInternal(
        existing: PlaylistEntity,
        sourceUri: String,
        isSecret: Boolean,
        makeActive: Boolean
    ): PlaylistCoverSlot? {
        val coverId = UUID.randomUUID().toString()
        val persisted = images.persistSlot(
            sourceUri = sourceUri,
            namespace = UserImageStore.NS_PLAYLISTS,
            key = existing.id,
            slotId = coverId
        ) ?: sourceUri
        val order = dao.maxCoverSortOrder(existing.id) + 1
        val row = PlaylistCoverEntity(
            id = coverId,
            playlistId = existing.id,
            uri = persisted,
            isSecret = isSecret,
            sortOrder = order,
            createdAtMs = System.currentTimeMillis()
        )
        dao.upsertCover(row)
        if (makeActive) {
            dao.upsert(
                existing.copy(
                    customImageUri = persisted,
                    activeCoverId = coverId,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        } else {
            touch(existing.id)
        }
        return PlaylistCoverSlot(
            id = coverId,
            uri = persisted,
            isSecret = isSecret,
            sortOrder = order,
            isActive = makeActive
        )
    }

    suspend fun setActiveCover(playlistId: String, coverId: String) =
        withContext(Dispatchers.IO) {
            val existing = dao.get(playlistId) ?: return@withContext
            val cover = dao.getCover(coverId) ?: return@withContext
            if (cover.playlistId != playlistId) return@withContext
            dao.upsert(
                existing.copy(
                    customImageUri = cover.uri,
                    activeCoverId = cover.id,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }

    suspend fun setCoverSecret(coverId: String, isSecret: Boolean) =
        withContext(Dispatchers.IO) {
            val cover = dao.getCover(coverId) ?: return@withContext
            dao.upsertCover(cover.copy(isSecret = isSecret))
            // If marking active public cover as secret, keep it active but flag secret.
            touch(cover.playlistId)
        }

    suspend fun removeCover(playlistId: String, coverId: String) =
        withContext(Dispatchers.IO) {
            val existing = dao.get(playlistId) ?: return@withContext
            val cover = dao.getCover(coverId) ?: return@withContext
            if (cover.playlistId != playlistId) return@withContext
            images.deleteSlot(UserImageStore.NS_PLAYLISTS, playlistId, coverId)
            dao.deleteCover(coverId)
            if (existing.activeCoverId == coverId) {
                val next = dao.getCovers(playlistId)
                    .firstOrNull { !it.isSecret }
                    ?: dao.getCovers(playlistId).firstOrNull()
                dao.upsert(
                    existing.copy(
                        customImageUri = next?.uri,
                        activeCoverId = next?.id,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            } else {
                touch(playlistId)
            }
        }

    /**
     * Privacy: secret covers are session-only. When the app backgrounds, the
     * phone locks, or the process starts cold, restore each playlist that has a
     * secret cover active back to its first **public** cover (or none).
     */
    suspend fun resetSecretActiveCoversToPublic(): Int = withContext(Dispatchers.IO) {
        val secrets = dao.getActiveSecretCovers()
        if (secrets.isEmpty()) return@withContext 0
        var changed = 0
        for (secret in secrets) {
            val playlist = dao.get(secret.playlistId) ?: continue
            if (playlist.activeCoverId != secret.id) continue
            val public = dao.getCovers(secret.playlistId).firstOrNull { !it.isSecret }
            dao.upsert(
                playlist.copy(
                    customImageUri = public?.uri,
                    activeCoverId = public?.id,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            changed++
        }
        changed
    }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            val covers = dao.getCovers(id)
            covers.forEach { images.deleteSlot(UserImageStore.NS_PLAYLISTS, id, it.id) }
            images.delete(UserImageStore.NS_PLAYLISTS, id)
            dao.clearCovers(id)
            dao.clearTracks(id)
            dao.delete(id)
        }

    suspend fun addSongs(id: String, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            catalog.ensureTracksPresent(songs)
            val existing = dao.getTracks(id).map { it.songKey }.toMutableList()
            songs.forEach { s ->
                val k = s.songKey
                if (k !in existing) existing.add(k)
            }
            dao.replaceTracks(id, existing)
            touch(id)
        }

    suspend fun removeSongs(id: String, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val drop = songs.map { it.songKey }.toHashSet()
            val remaining = dao.getTracks(id).map { it.songKey }.filterNot { it in drop }
            dao.replaceTracks(id, remaining)
            touch(id)
        }

    suspend fun playlistsContaining(songs: List<Song>): Set<String> =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext emptySet()
            dao.playlistIdsContaining(songs.first().songKey).toSet()
        }

    suspend fun removeAt(id: String, position: Int) =
        withContext(Dispatchers.IO) {
            val keys = dao.getTracks(id).map { it.songKey }.toMutableList()
            if (position !in keys.indices) return@withContext
            keys.removeAt(position)
            dao.replaceTracks(id, keys)
            touch(id)
        }

    /**
     * Remove the [occurrence]-th copy of [songKey] (0-based). Safer than a raw
     * list index when Compose gesture detectors hold a stale position.
     */
    suspend fun removeOccurrence(id: String, songKey: String, occurrence: Int = 0) =
        withContext(Dispatchers.IO) {
            val keys = dao.getTracks(id).map { it.songKey }.toMutableList()
            var seen = 0
            val pos = keys.indices.firstOrNull { i ->
                if (keys[i] != songKey) false
                else (seen++ == occurrence)
            } ?: return@withContext
            keys.removeAt(pos)
            dao.replaceTracks(id, keys)
            touch(id)
        }

    suspend fun move(id: String, from: Int, to: Int) =
        withContext(Dispatchers.IO) {
            val keys = dao.getTracks(id).map { it.songKey }.toMutableList()
            if (from !in keys.indices || to !in keys.indices || from == to) return@withContext
            val item = keys.removeAt(from)
            keys.add(to, item)
            dao.replaceTracks(id, keys)
            touch(id)
        }

    suspend fun replaceOrder(id: String, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            dao.replaceTracks(id, songs.map { it.songKey })
            touch(id)
        }

    private suspend fun touch(id: String) {
        val existing = dao.get(id) ?: return
        dao.upsert(existing.copy(updatedAtMs = System.currentTimeMillis()))
    }

    private fun PlaylistEntity.toPlaylist(trackCount: Int, songs: List<Song>) = Playlist(
        id = id,
        name = name,
        description = description,
        customImageUri = customImageUri,
        activeCoverId = activeCoverId,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        trackCount = trackCount,
        songs = songs
    )

    companion object {
        fun coverFor(playlist: Playlist): PlaylistCover {
            playlist.customImageUri?.let {
                return PlaylistCover(customUri = Uri.parse(it))
            }
            val unique = LinkedHashMap<String, Uri>()
            for (song in playlist.songs) {
                val art = song.albumArtUri ?: continue
                val key = albumKey(song.album, song.effectiveAlbumArtist)
                    .ifEmpty { art.toString() }
                if (key !in unique) {
                    unique[key] = art
                    if (unique.size >= 4) break
                }
            }
            return PlaylistCover(artUris = unique.values.toList())
        }
    }
}
