package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Desktop playlists: JSON + cover files on disk.
 * Secret covers stay in the file but are not the active slot after a cold start.
 */
class DesktopPlaylistStore(configDir: String) {
    private val root = File(configDir, "playlists")
    private val jsonFile = File(root, "playlists.json")
    private val coversDir = File(root, "covers")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _playlists = MutableStateFlow<List<DesktopPlaylist>>(emptyList())
    val playlists: StateFlow<List<DesktopPlaylist>> = _playlists.asStateFlow()

    init {
        root.mkdirs()
        coversDir.mkdirs()
        load()
        resetSecretActiveCoversToPublic()
    }

    fun get(id: String): DesktopPlaylist? = _playlists.value.firstOrNull { it.id == id }

    fun create(name: String, description: String? = null, trackIds: List<String> = emptyList()): DesktopPlaylist {
        val now = System.currentTimeMillis()
        val pl = DesktopPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "New playlist" },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            trackIds = trackIds,
            covers = emptyList(),
            activeCoverId = null,
            createdAtMs = now,
            updatedAtMs = now
        )
        _playlists.value = listOf(pl) + _playlists.value
        persist()
        return pl
    }

    fun rename(id: String, name: String, description: String?) {
        update(id) {
            it.copy(
                name = name.trim().ifEmpty { it.name },
                description = description?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }

    fun setTracks(id: String, trackIds: List<String>) {
        update(id) { it.copy(trackIds = trackIds.distinct()) }
    }

    fun addTracks(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        update(id) { pl ->
            val existing = pl.trackIds.toMutableList()
            val seen = existing.toHashSet()
            val snaps = pl.snapshots.toMutableList()
            for (t in tracks) {
                val already = t.playlistKeys().any { it in seen }
                if (!already) {
                    existing += t.id
                    seen += t.playlistKeys()
                }
                val i = snaps.indexOfFirst { s ->
                    s.id == t.id || s.catalogKey() == t.catalogKey() || t.id in s.playlistKeys()
                }
                if (i < 0) snaps += t else snaps[i] = t
            }
            pl.copy(trackIds = existing, snapshots = snaps)
        }
    }

    fun remember(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        var changed = false
        val next = _playlists.value.map { pl ->
            var snaps = pl.snapshots
            for (t in tracks) {
                if (pl.trackIds.none { it in t.playlistKeys() }) continue
                val i = snaps.indexOfFirst { s -> s.id == t.id || s.catalogKey() == t.catalogKey() }
                snaps = if (i < 0) snaps + t else snaps.toMutableList().also { it[i] = t }
                changed = true
            }
            if (snaps === pl.snapshots) pl else pl.copy(snapshots = snaps)
        }
        if (changed) {
            _playlists.value = next
            persist()
        }
    }

    fun playlistsContaining(trackId: String): Set<String> =
        _playlists.value.filter { trackId in it.trackIds }.map { it.id }.toSet()

    fun playlistsContaining(track: Track): Set<String> {
        val keys = track.playlistKeys()
        return _playlists.value.filter { pl -> pl.trackIds.any { it in keys } }.map { it.id }.toSet()
    }

    fun removeTracks(id: String, tracks: List<Track>) {
        val drop = tracks.flatMap { it.playlistKeys() }.toHashSet()
        update(id) {
            it.copy(
                trackIds = it.trackIds.filterNot { key -> key in drop },
                snapshots = it.snapshots.filterNot { t -> t.playlistKeys().any { k -> k in drop } }
            )
        }
    }

    fun moveTrack(id: String, from: Int, to: Int) {
        update(id) {
            val t = it.trackIds.toMutableList()
            if (from !in t.indices || to !in t.indices) return@update it
            val item = t.removeAt(from)
            t.add(to, item)
            it.copy(trackIds = t)
        }
    }

    fun addCover(playlistId: String, source: File, isSecret: Boolean, makeActive: Boolean = !isSecret): DesktopCover? {
        val pl = get(playlistId) ?: return null
        if (!source.isFile) return null
        val coverId = UUID.randomUUID().toString()
        val destDir = File(coversDir, playlistId).also { it.mkdirs() }
        val dest = File(destDir, "$coverId.jpg")
        copyAsJpeg(source, dest)
        val cover = DesktopCover(
            id = coverId,
            path = dest.absolutePath,
            isSecret = isSecret,
            sortOrder = (pl.covers.maxOfOrNull { it.sortOrder } ?: -1) + 1
        )
        update(playlistId) {
            val next = it.copy(covers = it.covers + cover)
            if (makeActive) next.copy(activeCoverId = coverId) else next
        }
        return cover
    }

    fun setActiveCover(playlistId: String, coverId: String) {
        update(playlistId) { pl ->
            if (pl.covers.none { it.id == coverId }) pl
            else pl.copy(activeCoverId = coverId)
        }
    }

    fun setCoverSecret(playlistId: String, coverId: String, secret: Boolean) {
        update(playlistId) { pl ->
            pl.copy(covers = pl.covers.map { if (it.id == coverId) it.copy(isSecret = secret) else it })
        }
    }

    fun removeCover(playlistId: String, coverId: String) {
        val pl = get(playlistId) ?: return
        val cover = pl.covers.firstOrNull { it.id == coverId } ?: return
        File(cover.path).delete()
        update(playlistId) {
            val remaining = it.covers.filterNot { c -> c.id == coverId }
            val active = if (it.activeCoverId == coverId) {
                remaining.firstOrNull { c -> !c.isSecret }?.id ?: remaining.firstOrNull()?.id
            } else it.activeCoverId
            it.copy(covers = remaining, activeCoverId = active)
        }
    }

    fun delete(id: String) {
        File(coversDir, id).deleteRecursively()
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }

    fun resetSecretActiveCoversToPublic() {
        var changed = false
        val next = _playlists.value.map { pl ->
            val active = pl.covers.firstOrNull { it.id == pl.activeCoverId }
            if (active?.isSecret != true) return@map pl
            changed = true
            val pub = pl.covers.firstOrNull { !it.isSecret }
            pl.copy(activeCoverId = pub?.id)
        }
        if (changed) {
            _playlists.value = next
            persist()
        }
    }

    private fun update(id: String, transform: (DesktopPlaylist) -> DesktopPlaylist) {
        _playlists.value = _playlists.value.map {
            if (it.id != id) it
            else transform(it).copy(updatedAtMs = System.currentTimeMillis())
        }
        persist()
    }

    private fun load() {
        if (!jsonFile.exists()) return
        runCatching {
            val file = json.decodeFromString<PlaylistFile>(jsonFile.readText())
            _playlists.value = file.playlists
        }
    }

    private fun persist() {
        runCatching {
            root.mkdirs()
            jsonFile.writeText(json.encodeToString(PlaylistFile(_playlists.value)))
        }
    }

    private fun copyAsJpeg(source: File, dest: File) {
        dest.parentFile.mkdirs()
        val img = ImageIO.read(source)
        if (img != null) {
            ImageIO.write(img, "jpg", dest)
        } else {
            source.copyTo(dest, overwrite = true)
        }
    }

    @Serializable
    private data class PlaylistFile(val playlists: List<DesktopPlaylist> = emptyList())
}

@Serializable
data class DesktopPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val trackIds: List<String> = emptyList(),
    val covers: List<DesktopCover> = emptyList(),
    val activeCoverId: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val snapshots: List<Track> = emptyList()
) {
    fun artworkUri(library: List<Track>): String? {
        val active = covers.firstOrNull { it.id == activeCoverId } ?: covers.firstOrNull { !it.isSecret }
        if (active != null) return File(active.path).toURI().toString()
        return tracks(library).firstOrNull()?.artworkUri
    }

    fun tracks(library: List<Track>): List<Track> {
        if (trackIds.isEmpty()) return emptyList()
        val byKey = HashMap<String, Track>()
        for (t in library) {
            byKey.putIfAbsent(t.id, t)
            byKey.putIfAbsent(t.catalogKey(), t)
        }
        for (t in snapshots) {
            byKey.putIfAbsent(t.id, t)
            byKey.putIfAbsent(t.catalogKey(), t)
        }
        return trackIds.mapNotNull { byKey[it] }
    }
}

@Serializable
data class DesktopCover(
    val id: String,
    val path: String,
    val isSecret: Boolean = false,
    val sortOrder: Int = 0
)
