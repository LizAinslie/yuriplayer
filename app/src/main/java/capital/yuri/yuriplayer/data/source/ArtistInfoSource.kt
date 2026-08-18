package capital.yuri.yuriplayer.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

interface ArtistInfoSource {
    val id: String
    val displayName: String get() = id

    suspend fun fetchProfile(artistName: String): ArtistProfile?

    suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind = ArtistImageKind.PROFILE
    ): List<ArtistImageCandidate> = emptyList()
}

enum class ArtistImageKind { PROFILE, BANNER }

data class ArtistImageCandidate(
    val url: String,
    val sourceId: String,
    val label: String = sourceId,
    val width: Int? = null,
    val height: Int? = null
)

class ArtistInfoService(
    private val sources: List<ArtistInfoSource>,
    private val bandsintown: BandsintownClient
) {
    suspend fun resolveProfile(artistName: String): ArtistProfile? = coroutineScope {
        val parts = sources.map { src ->
            async { runCatching { src.fetchProfile(artistName) }.getOrNull() }
        }.awaitAll().filterNotNull()
        parts.fold(null as ArtistProfile?) { acc, p -> mergeProfiles(artistName, acc, p) }
    }

    suspend fun gatherImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = coroutineScope {
        val lists = sources.map { src ->
            async {
                runCatching { src.fetchImageCandidates(artistName, kind) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        lists.flatten().forEach { c ->
            if (c.url.isBlank()) return@forEach
            // Skip empty / placeholder CDN paths
            if (c.url.contains("/images/artist//")) return@forEach
            if (c.url.contains("artist-default")) return@forEach

            val key = ArtistNameMatch.imageFingerprint(c.url)
            val existing = out[key]
            if (existing == null) {
                out[key] = c
                return@forEach
            }
            val newArea = (c.width ?: 0) * (c.height ?: 0).let { if (it > 0) it else ArtistNameMatch.imageSizeHint(c.url) }
            val oldArea = (existing.width ?: 0) * (existing.height ?: 0).let {
                if (it > 0) it else ArtistNameMatch.imageSizeHint(existing.url)
            }
            val existingThumb = existing.label.contains("thumb", true) ||
                existing.url.contains("thumb", true)
            val newThumb = c.label.contains("thumb", true) || c.url.contains("thumb", true)
            if (newArea > oldArea || (existingThumb && !newThumb)) {
                out[key] = c
            }
        }
        out.values.toList()
    }

    suspend fun upcomingEvents(artistName: String): List<ArtistEvent> =
        runCatching { bandsintown.upcomingEvents(artistName) }.getOrDefault(emptyList())

    private fun mergeProfiles(
        artistName: String,
        base: ArtistProfile?,
        incoming: ArtistProfile
    ): ArtistProfile {
        if (base == null) return incoming
        return base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = ArtistNameMatch.preferBio(artistName, base.bio, incoming.bio),
            imageUri = base.imageUri ?: incoming.imageUri,
            websiteUrl = base.websiteUrl ?: incoming.websiteUrl,
            links = (base.links + incoming.links)
                .distinctBy { ArtistNameMatch.linkFingerprint(it.url) },
            genres = (base.genres + incoming.genres)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() },
            source = listOf(base.source, incoming.source).filter { it.isNotBlank() }
                .distinct().joinToString(",")
        )
    }
}
