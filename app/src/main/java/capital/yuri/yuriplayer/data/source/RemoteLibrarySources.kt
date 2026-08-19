package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.db.SourceInstanceEntity

/**
 * Live [LibrarySource] bound to a persisted [SourceInstanceEntity].
 * Browse results are ephemeral — CatalogRepository.importToMyStuff persists favorites.
 */
class JellyfinLibrarySource(
    private val instance: SourceInstanceEntity,
    private val client: JellyfinClient
) : LibrarySource {
    override val id: String = "jellyfin:${instance.id}"
    override val displayName: String = instance.name
    override val type: SourceType = SourceType.JELLYFIN

    override suspend fun isAvailable(): Boolean {
        if (!instance.enabled) return false
        val url = instance.baseUrl ?: return false
        val user = instance.username ?: return false
        val secret = instance.secret ?: return false
        return client.authenticate(url, user, secret).isSuccess
    }

    override suspend fun scan(): LibrarySnapshot {
        val url = instance.baseUrl ?: error("Missing Jellyfin URL")
        val user = instance.username ?: error("Missing Jellyfin username")
        val secret = instance.secret ?: error("Missing Jellyfin secret")
        val session = client.authenticate(url, user, secret).getOrThrow()
        val songs = client.listAudioItems(session).getOrThrow()
        return LibrarySnapshot(
            sourceId = id,
            songs = songs,
            scannedAtMs = System.currentTimeMillis()
        )
    }
}

class SubsonicLibrarySource(
    private val instance: SourceInstanceEntity,
    private val client: SubsonicClient
) : LibrarySource {
    override val id: String = "subsonic:${instance.id}"
    override val displayName: String = instance.name
    override val type: SourceType =
        if (instance.type.equals(SourceType.NAVIDROME.name, true)) SourceType.NAVIDROME
        else SourceType.SUBSONIC

    override suspend fun isAvailable(): Boolean {
        if (!instance.enabled) return false
        val session = sessionOrNull() ?: return false
        return client.ping(session).isSuccess
    }

    override suspend fun scan(): LibrarySnapshot {
        val session = sessionOrNull() ?: error("Incomplete Subsonic instance")
        client.ping(session).getOrThrow()
        val songs = client.listAllSongs(session).getOrThrow()
        return LibrarySnapshot(
            sourceId = id,
            songs = songs,
            scannedAtMs = System.currentTimeMillis()
        )
    }

    private fun sessionOrNull(): SubsonicClient.Session? {
        val url = instance.baseUrl ?: return null
        val user = instance.username ?: return null
        val secret = instance.secret ?: return null
        return SubsonicClient.Session(
            baseUrl = url,
            username = user,
            password = secret
        )
    }
}

/**
 * Builds [LibrarySource] plugins from DB rows + local source.
 * Call after source_instances changes to refresh the registry inputs.
 */
class LibrarySourceFactory(
    private val local: LocalLibrarySource,
    private val jellyfinClient: JellyfinClient,
    private val subsonicClient: SubsonicClient,
    private val instances: SourceInstanceRepository
) {
    suspend fun buildAll(): List<LibrarySource> {
        val remote = instances.getAll().mapNotNull { row ->
            when (SourceType.from(row.type)) {
                SourceType.JELLYFIN -> JellyfinLibrarySource(row, jellyfinClient)
                SourceType.SUBSONIC, SourceType.NAVIDROME ->
                    SubsonicLibrarySource(row, subsonicClient)
                else -> null
            }
        }
        return listOf(local) + remote
    }
}
