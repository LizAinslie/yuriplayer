package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import java.util.UUID

/**
 * Pull artist Primary (profile) and Backdrop (banner) from configured Jellyfin servers.
 * Respects [UserImageStore] clear markers upstream — this only supplies candidates.
 */
class JellyfinArtistImageSource(
    private val jellyfinClient: JellyfinClient,
    private val instances: SourceInstanceRepository
) : ArtistInfoSource {

    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = null

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val q = artistName.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val servers = instances.getAll()
            .filter { it.enabled && it.type.equals(SourceType.JELLYFIN.name, ignoreCase = true) }
        if (servers.isEmpty()) return@withContext emptyList()

        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (server in servers) {
            val session = openSession(server) ?: continue
            val hits = searchArtists(session, q)
            for (hit in hits) {
                val url = when (kind) {
                    ArtistImageKind.PROFILE -> hit.primaryUrl
                    ArtistImageKind.BANNER -> hit.backdropUrl ?: hit.primaryUrl
                } ?: continue
                if (url.isBlank()) continue
                val fp = ArtistNameMatch.imageFingerprint(url)
                out.putIfAbsent(
                    fp,
                    ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = "Jellyfin · ${server.name.ifBlank { "server" }} · ${hit.name}",
                        width = if (kind == ArtistImageKind.BANNER) 1920 else 512,
                        height = if (kind == ArtistImageKind.BANNER) 1080 else 512
                    )
                )
            }
        }
        out.values.toList()
    }

    private suspend fun openSession(server: SourceInstanceEntity): JellyfinClient.Session? {
        val user = server.username?.trim().orEmpty()
        val secret = server.secret.orEmpty()
        if (user.isEmpty() || secret.isEmpty() || server.baseUrl.isNullOrBlank()) return null
        return jellyfinClient.authenticate(
            baseUrl = server.baseUrl!!,
            username = user,
            password = secret
        ).getOrElse {
            Log.w(TAG, "auth failed for ${server.name}: ${it.message}")
            null
        }
    }

    private data class ArtistHit(
        val name: String,
        val primaryUrl: String?,
        val backdropUrl: String?
    )

    private suspend fun searchArtists(
        session: JellyfinClient.Session,
        query: String
    ): List<ArtistHit> {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull() ?: return emptyList()
        return try {
            val result by session.api.itemsApi.getItems(
                userId = userId,
                searchTerm = query,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ARTIST),
                fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                enableImages = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP),
                limit = 12
            )
            result.items.orEmpty().mapNotNull { item ->
                val id = item.id?.toString() ?: return@mapNotNull null
                val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!ArtistNameMatch.looksLike(query, name)) return@mapNotNull null
                val hasPrimary = item.imageTags?.containsKey(ImageType.PRIMARY) == true
                val hasBackdrop = !item.backdropImageTags.isNullOrEmpty() ||
                    item.imageTags?.containsKey(ImageType.BACKDROP) == true
                ArtistHit(
                    name = name,
                    primaryUrl = if (hasPrimary) {
                        jellyfinClient.artistImageUrl(session, id, maxWidth = 512)
                    } else null,
                    backdropUrl = if (hasBackdrop) {
                        jellyfinClient.artistBackdropUrl(session, id, maxWidth = 1920)
                    } else null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchArtists failed: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "JellyfinArtistImg"
    }
}
