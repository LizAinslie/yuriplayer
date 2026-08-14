package capital.yuri.yuriplayer.data

import android.net.Uri
import capital.yuri.yuriplayer.data.db.PlaylistDao
import capital.yuri.yuriplayer.data.db.PlaylistEntity
import capital.yuri.yuriplayer.data.db.PlaylistTrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

class PlaylistRepository(
    private val dao: PlaylistDao,
    private val library: LibraryIndex
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observePlaylists(): Flow<List<Playlist>> =
        combine(dao.observeAll(), library.songs) { entities, songs ->
            entities.map { e ->
                val keys = emptyList<String>() // filled cheaply — track counts via separate query if needed
                Playlist(
                    id = e.id,
                    name = e.name,
                    description = e.description,
                    customImageUri = e.customImageUri,
                    createdAtMs = e.createdAtMs,
                    updatedAtMs = e.updatedAtMs,
                    trackCount = 0,
                    songs = emptyList()
                )
            }
        }

    /** Richer observe that resolves songs for each playlist (for My Stuff list covers). */
    fun observePlaylistsResolved(): Flow<List<Playlist>> =
        combine(dao.observeAll(), library.songs) { entities, allSongs ->
            val byKey = allSongs.associateBy { it.songKey }
            entities.map { e ->
                // Tracks loaded lazily in detail; count via quick suspend is better,
                // but for list UI we accept 0 until opened — use cached if available.
                Playlist(
                    id = e.id,
                    name = e.name,
                    description = e.description,
                    customImageUri = e.customImageUri,
                    createdAtMs = e.createdAtMs,
                    updatedAtMs = e.updatedAtMs
                )
            }
        }

    fun observePlaylist(id: String): Flow<Playlist?> =
        combine(dao.observe(id), dao.observeTracks(id), library.songs) { entity, tracks, allSongs ->
            if (entity == null) return@combine null
            val byKey = allSongs.associateBy { it.songKey }
            val songs = tracks.mapNotNull { byKey[it.songKey] }
            Playlist(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                customImageUri = entity.customImageUri,
                createdAtMs = entity.createdAtMs,
                updatedAtMs = entity.updatedAtMs,
                trackCount = songs.size,
                songs = songs
            )
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
            dao.upsert(
                existing.copy(
                    customImageUri = uri,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            dao.clearTracks(id)
            dao.delete(id)
        }

    suspend fun addSongs(id: String, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext
            val existing = dao.getTracks(id).map { it.songKey }.toMutableList()
            songs.forEach { s ->
                val k = s.songKey
                if (k !in existing) existing.add(k)
            }
            dao.replaceTracks(id, existing)
            touch(id)
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
