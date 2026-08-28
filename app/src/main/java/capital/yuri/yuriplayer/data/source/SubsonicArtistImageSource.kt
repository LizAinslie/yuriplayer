package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artist profile art from enabled Subsonic / OpenSubsonic (Navidrome) servers.
 * Banner is the same cover when the server has no separate backdrop.
 */
class SubsonicArtistImageSource(
    private val client: SubsonicClient,
    private val instances: SourceInstanceRepository
) : ArtistInfoSource {

    override val id: String = "subsonic"
    override val displayName: String = "Subsonic / Navidrome"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = null

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val q = artistName.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val servers = instances.getAll().filter { row ->
            row.enabled && SourceType.isSubsonicFamily(SourceType.from(row.type))
        }
        if (servers.isEmpty()) return@withContext emptyList()

        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (server in servers) {
            val session = openSession(server) ?: continue
            val hits = client.searchArtists(session, q, count = 12).getOrElse { emptyList() }
            for (hit in hits) {
                if (!ArtistNameMatch.looksLike(q, hit.name)) continue
                val url = client.coverUrl(session, hit.coverArt, size = if (kind == ArtistImageKind.BANNER) 1200 else 512)
                    ?: continue
                if (url.isBlank()) continue
                val fp = ArtistNameMatch.imageFingerprint(url)
                out.putIfAbsent(
                    fp,
                    ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = "Subsonic · ${server.name.ifBlank { "server" }} · ${hit.name}",
                        width = if (kind == ArtistImageKind.BANNER) 1200 else 512,
                        height = if (kind == ArtistImageKind.BANNER) 1200 else 512
                    )
                )
            }
        }
        out.values.toList()
    }

    private suspend fun openSession(server: SourceInstanceEntity): SubsonicClient.Session? {
        val url = server.baseUrl ?: return null
        val user = server.username?.trim().orEmpty()
        val secret = server.secret.orEmpty()
        if (user.isEmpty() || secret.isEmpty()) return null
        val base = SubsonicClient.Session(
            baseUrl = SourceInstanceRepository.normalizeBaseUrl(url),
            username = user,
            password = secret
        )
        return client.ping(base).getOrElse {
            log.w { "ping failed for ${server.name}: ${it.message}" }
            null
        }
    }

    companion object {
        private val log = yuriLog("SubsonicArtistImg")
    }
}
