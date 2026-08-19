package capital.yuri.yuriplayer.data

import android.net.Uri
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
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val trackCount: Int = 0,
    val songs: List<Song> = emptyList()
)

/**
 * Cover strategy (Spotify-ish):
 * 1. [customImageUri] if set
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
 *
 * List observation is deliberately cheap: entity + SQL COUNT only. Full song
 * resolution only happens for a single playlist detail.
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val catalog: CatalogRepository,
    private val images: UserImageStore
) {

    /** Cheap list for sheets / My Stuff — no song resolution, no Main work. */
    fun observePlaylists(): Flow<List<Playlist>> =
        combine(dao.observeAll(), dao.observeTrackCounts()) { entities, counts ->
            val countMap = counts.associate { it.playlistId to it.trackCount }
            entities.map { e ->
                Playlist(
                    id = e.id,
                    name = e.name,
                    description = e.description,
                    customImageUri = e.customImageUri,
                    createdAtMs = e.createdAtMs,
                    updatedAtMs = e.updatedAtMs,
                    trackCount = countMap[e.id] ?: 0,
                    songs = emptyList()
                )
            }
        }.flowOn(Dispatchers.Default)

    fun observePlaylistsResolved(): Flow<List<Playlist>> = observePlaylists()

    /** Full resolve for one playlist (detail screen / play). Runs off Main. */
    fun observePlaylist(id: String): Flow<Playlist?> =
        combine(dao.observe(id), dao.observeTracks(id)) { entity, tracks ->
            entity to tracks
        }.map { (entity, tracks) ->
            if (entity == null) return@map null
            val songs = resolveSongs(tracks)
            Playlist(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                customImageUri = entity.customImageUri,
                createdAtMs = entity.createdAtMs,
                updatedAtMs = entity.updatedAtMs,
                trackCount = tracks.size,
                songs = songs
            )
        }.flowOn(Dispatchers.IO)

    /** Exact songKey match against the full catalog (local + Jellyfin/etc). */
    private suspend fun resolveSongs(tracks: List<PlaylistTrackEntity>): List<Song> {
        if (tracks.isEmpty()) return emptyList()
        val keys = tracks.map { it.songKey }
        val byKey = catalog.getSongsByKeys(keys).associateBy { it.songKey }
        // Preserve playlist order; skip missing keys (deleted files)
        return tracks.mapNotNull { byKey[it.songKey] }
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
            Playlist(
                id = id,
                name = entity.name,
                description = entity.description,
                createdAtMs = now,
                updatedAtMs = now
            )
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

    suspend fun setCustomImage(id: String, uri: String?) =
        withContext(Dispatchers.IO) {
            val existing = dao.get(id) ?: return@withContext
            val persisted = if (uri.isNullOrBlank()) {
                images.delete(UserImageStore.NS_PLAYLISTS, id)
                null
            } else {
                images.persist(uri, UserImageStore.NS_PLAYLISTS, id) ?: uri
            }
            dao.upsert(
                existing.copy(
                    customImageUri = persisted,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            images.delete(UserImageStore.NS_PLAYLISTS, id)
            dao.clearTracks(id)
            dao.delete(id)
        }

    /**
     * Add songs by songKey. Works for local **and** Jellyfin/Subsonic keys
     * because resolution goes through the catalog, not LibraryIndex.
     */
    suspend fun addSongs(id: String, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            // Ensure remote tracks exist in catalog so they resolve later
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
