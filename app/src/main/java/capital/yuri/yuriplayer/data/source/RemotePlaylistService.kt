package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.Playlist
import capital.yuri.yuriplayer.data.PlaylistRepository
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.db.CatalogSources
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity

data class RemotePlaylist(
    val sourceType: String,
    val sourceInstanceId: Long,
    val sourceName: String,
    val remoteId: String,
    val name: String,
    val songCount: Int,
    val coverUrl: String?
) {
    val stableId: String get() = "$sourceType:$sourceInstanceId:$remoteId"
}

class RemotePlaylistService(
    private val instances: SourceInstanceRepository,
    private val jellyfin: JellyfinClient,
    private val subsonic: SubsonicClient,
    private val playlists: PlaylistRepository
) {
    suspend fun listAll(): List<RemotePlaylist> {
        val out = ArrayList<RemotePlaylist>()
        for (row in instances.getAll()) {
            if (!row.enabled) continue
            when (SourceType.from(row.type)) {
                SourceType.JELLYFIN -> out += jellyfinPlaylists(row)
                SourceType.SUBSONIC, SourceType.NAVIDROME -> out += subsonicPlaylists(row)
                else -> Unit
            }
        }
        return out
    }

    suspend fun search(query: String): List<RemotePlaylist> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return listAll().filter { it.name.contains(q, ignoreCase = true) }
    }

    suspend fun importToLocal(remote: RemotePlaylist): Playlist? {
        val songs = loadSongs(remote)
        if (songs.isEmpty()) return null
        val created = playlists.create(remote.name)
        playlists.addSongs(created.id, songs)
        return created.copy(trackCount = songs.size, songs = songs)
    }

    suspend fun loadSongs(remote: RemotePlaylist): List<Song> {
        val row = instances.get(remote.sourceInstanceId) ?: return emptyList()
        return when (SourceType.from(row.type)) {
            SourceType.JELLYFIN -> {
                val session = jellyfinSession(row) ?: return emptyList()
                jellyfin.playlistSongs(session, remote.remoteId).getOrElse { emptyList() }
            }
            SourceType.SUBSONIC, SourceType.NAVIDROME -> {
                val session = subsonicSession(row) ?: return emptyList()
                subsonic.playlistSongs(session, remote.remoteId).getOrElse { emptyList() }
            }
            else -> emptyList()
        }
    }

    private suspend fun jellyfinPlaylists(row: SourceInstanceEntity): List<RemotePlaylist> {
        val session = jellyfinSession(row) ?: return emptyList()
        return jellyfin.listPlaylists(session).getOrElse { emptyList() }.map { p ->
            RemotePlaylist(
                sourceType = CatalogSources.JELLYFIN,
                sourceInstanceId = row.id,
                sourceName = row.name,
                remoteId = p.id,
                name = p.name,
                songCount = p.songCount,
                coverUrl = p.coverUrl
            )
        }
    }

    private suspend fun subsonicPlaylists(row: SourceInstanceEntity): List<RemotePlaylist> {
        val session = subsonicSession(row) ?: return emptyList()
        return subsonic.listPlaylists(session).getOrElse { emptyList() }.map { p ->
            RemotePlaylist(
                sourceType = row.type,
                sourceInstanceId = row.id,
                sourceName = row.name,
                remoteId = p.id,
                name = p.name,
                songCount = p.songCount,
                coverUrl = subsonic.coverUrl(session, p.coverArt, 256)
            )
        }
    }

    private suspend fun jellyfinSession(row: SourceInstanceEntity): JellyfinClient.Session? {
        val url = row.baseUrl ?: return null
        val user = row.username ?: return null
        val secret = row.secret ?: return null
        return jellyfin.authenticate(url, user, secret).getOrElse {
            Log.w(TAG, "jellyfin auth for playlists failed: ${it.message}")
            null
        }
    }

    private fun subsonicSession(row: SourceInstanceEntity): SubsonicClient.Session? {
        val url = row.baseUrl ?: return null
        val user = row.username ?: return null
        val secret = row.secret ?: return null
        return SubsonicClient.Session(
            baseUrl = SourceInstanceRepository.normalizeBaseUrl(url),
            username = user,
            password = secret
        )
    }

    companion object {
        private const val TAG = "RemotePlaylists"
    }
}
