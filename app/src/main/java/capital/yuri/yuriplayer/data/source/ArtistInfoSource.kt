package capital.yuri.yuriplayer.data.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Plugin-friendly SPI for artist metadata / images.
 * Built-in sources implement this; future JAR plugins register more via Koin.
 */
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
        parts.fold(null as ArtistProfile?) { acc, p -> mergeProfiles(acc, p) }
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
            val key = c.url.substringBefore("?") // collapse size variants of same asset
            if (key !in out && c.url !in out) out[c.url] = c
        }
        out.values.toList()
    }

    suspend fun upcomingEvents(artistName: String): List<ArtistEvent> =
        runCatching { bandsintown.upcomingEvents(artistName) }.getOrDefault(emptyList())

    private fun mergeProfiles(base: ArtistProfile?, incoming: ArtistProfile): ArtistProfile {
        if (base == null) return incoming
        return base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = listOfNotNull(base.bio, incoming.bio).maxByOrNull { it.length },
            imageUri = base.imageUri ?: incoming.imageUri,
            websiteUrl = base.websiteUrl ?: incoming.websiteUrl,
            links = (base.links + incoming.links).distinctBy { it.url.lowercase() },
            genres = (base.genres + incoming.genres)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() },
            source = listOf(base.source, incoming.source).filter { it.isNotBlank() }
                .distinct().joinToString(",")
        )
    }
}
