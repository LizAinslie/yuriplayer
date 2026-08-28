package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.indexKeys
import capital.yuri.yuriplayer.core.library.playlistKeys
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.Song
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
    private val log = yuriLog("Playlist")

    private val root = File(configDir, "playlists")
    private val jsonFile = File(root, "playlists.json")
    private val coversDir = File(root, "covers")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

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
        update(id) { pl ->
            val byId = pl.orderedEntries().associateBy { it.snapshot.songKey }
            val next = trackIds.mapIndexedNotNull { i, key ->
                byId[key]?.copy(ordinal = i)
            }
            pl.withEntries(next)
        }
    }

    fun addTracks(id: String, tracks: List<Song>) {
        if (tracks.isEmpty()) return
        update(id) { pl ->
            val current = pl.orderedEntries().toMutableList()
            var nextOrdinal = (current.maxOfOrNull { it.ordinal } ?: -1) + 1
            for (t in tracks) {
                PlaylistLog.add(pl.name, t)
                val i = current.indexOfFirst { e -> e.matches(t) }
                if (i >= 0) {
                    current[i] = current[i].copy(
                        snapshot = t,
                        keys = t.indexKeys().toList()
                    )
                } else {
                    current += PlaylistEntry(
                        ordinal = nextOrdinal++,
                        snapshot = t,
                        keys = t.indexKeys().toList()
                    )
                }
            }
            pl.withEntries(current)
        }
    }

    fun remember(tracks: List<Song>) {
        if (tracks.isEmpty()) return
        var any = false
        val next = _playlists.value.map { pl ->
            val current = pl.orderedEntries().toMutableList()
            var hit = false
            for (t in tracks) {
                val i = current.indexOfFirst { e -> e.matches(t) }
                if (i < 0) continue
                current[i] = current[i].copy(snapshot = t, keys = t.indexKeys().toList())
                hit = true
            }
            if (!hit) pl else {
                any = true
                pl.withEntries(current)
            }
        }
        if (any) {
            _playlists.value = next
            persist()
        }
    }

    fun playlistsContaining(trackId: String): Set<String> =
        playlistsContaining(Song(id = 0, contentUri = "", path = trackId))

    fun playlistsContaining(track: Song): Set<String> {
        val keys = track.indexKeys()
        return _playlists.value.filter { pl ->
            pl.orderedEntries().any { e -> e.allKeys().any { it in keys } }
        }.map { it.id }.toSet()
    }

    fun removeTracks(id: String, tracks: List<Song>) {
        val drop = tracks.flatMap { it.indexKeys() }.toHashSet()
        update(id) { pl ->
            pl.withEntries(
                pl.orderedEntries().filterNot { e -> e.allKeys().any { it in drop } }
            )
        }
    }

    fun moveTrack(id: String, from: Int, to: Int) {
        update(id) { pl ->
            val list = pl.orderedEntries().toMutableList()
            if (from !in list.indices || to !in list.indices || from == to) return@update pl
            val item = list.removeAt(from)
            list.add(to, item)
            pl.withEntries(list)
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
            _playlists.value = file.playlists.map { it.withEntries(it.orderedEntries()) }
        }.onFailure {
            log.w { "load failed: ${it.message}" }
        }
    }

    private fun persist() {
        runCatching {
            root.mkdirs()
            jsonFile.writeText(json.encodeToString(PlaylistFile(_playlists.value)))
        }.onFailure {
            log.w { "save failed: ${it.message}" }
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
    val snapshots: List<Song> = emptyList(),
    val entries: List<PlaylistEntry> = emptyList()
) {
    fun artworkUri(library: List<Song>): String? {
        val active = covers.firstOrNull { it.id == activeCoverId } ?: covers.firstOrNull { !it.isSecret }
        if (active != null) return File(active.path).toURI().toString()
        return tracks(library).firstOrNull()?.albumArtUri
    }

    fun orderedEntries(): List<PlaylistEntry> {
        if (entries.isNotEmpty()) return entries.sortedBy { it.ordinal }
        return migrateLegacyEntries()
    }

    fun withEntries(list: List<PlaylistEntry>): DesktopPlaylist {
        val compact = list.sortedBy { it.ordinal }.mapIndexed { i, e ->
            e.copy(ordinal = i, keys = e.snapshot.indexKeys().toList().ifEmpty { e.keys })
        }
        return copy(
            entries = compact,
            trackIds = compact.map { it.snapshot.songKey },
            snapshots = compact.map { it.snapshot }
        )
    }

    fun tracks(library: List<Song>): List<Song> {
        val index = HashMap<String, Song>()
        for (t in library) {
            for (k in t.indexKeys()) index.putIfAbsent(k, t)
        }
        val ordered = orderedEntries()
        val seen = HashSet<String>()
        val out = ArrayList<Song>(ordered.size)
        for (entry in ordered) {
            val live = entry.allKeys().firstNotNullOfOrNull { index[it] } ?: entry.snapshot
            if (live.indexKeys().any { it in seen }) continue
            seen += live.indexKeys()
            out += live
        }
        return out
    }

    private fun migrateLegacyEntries(): List<PlaylistEntry> {
        val byKey = HashMap<String, Song>()
        for (t in snapshots) {
            for (k in t.indexKeys()) byKey.putIfAbsent(k, t)
        }
        val used = HashSet<String>()
        val out = ArrayList<PlaylistEntry>()
        fun take(t: Song) {
            if (t.indexKeys().any { it in used }) return
            used += t.indexKeys()
            out += PlaylistEntry(
                ordinal = out.size,
                snapshot = t,
                keys = t.indexKeys().toList()
            )
        }
        for (key in trackIds) {
            val hit = byKey[key] ?: snapshots.firstOrNull { key in it.indexKeys() }
            if (hit != null) take(hit)
        }
        for (s in snapshots) take(s)
        return out
    }
}

@Serializable
data class PlaylistEntry(
    val ordinal: Int,
    val snapshot: Song,
    val keys: List<String> = emptyList()
) {
    fun allKeys(): Set<String> = buildSet {
        addAll(keys)
        addAll(snapshot.indexKeys())
    }

    fun matches(track: Song): Boolean {
        val theirs = track.indexKeys()
        return allKeys().any { it in theirs }
    }
}

@Serializable
data class DesktopCover(
    val id: String,
    val path: String,
    val isSecret: Boolean = false,
    val sortOrder: Int = 0
)
