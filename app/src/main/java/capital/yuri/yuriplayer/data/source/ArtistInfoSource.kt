package capital.yuri.yuriplayer.data.source

/**
 * Plugin-friendly SPI for artist metadata / images.
 * Built-in MB + Wikidata implement this; future JAR plugins can register more
 * via Koin modules (desktop plugin loader will load them the same way).
 */
interface ArtistInfoSource {
    /** Stable id e.g. "musicbrainz", "wikidata", "fanart". */
    val id: String

    /** Human label for Data sources UI. */
    val displayName: String get() = id

    suspend fun fetchProfile(artistName: String): ArtistProfile?

    /** Candidate image URLs (profile or banner). Empty if none. */
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

/**
 * Aggregates every [ArtistInfoSource] registered in Koin.
 * Call sites should depend on this, not individual providers.
 */
class ArtistInfoService(
    private val sources: List<ArtistInfoSource>
) {
    suspend fun resolveProfile(artistName: String): ArtistProfile? {
        var best: ArtistProfile? = null
        for (src in sources) {
            val hit = runCatching { src.fetchProfile(artistName) }.getOrNull() ?: continue
            best = mergeProfiles(best, hit)
        }
        return best
    }

    suspend fun gatherImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> {
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (src in sources) {
            val list = runCatching { src.fetchImageCandidates(artistName, kind) }
                .getOrDefault(emptyList())
            list.forEach { c -> if (c.url !in out) out[c.url] = c }
        }
        return out.values.toList()
    }

    private fun mergeProfiles(base: ArtistProfile?, incoming: ArtistProfile): ArtistProfile {
        if (base == null) return incoming
        return base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = incoming.bio ?: base.bio,
            imageUri = base.imageUri ?: incoming.imageUri,
            websiteUrl = base.websiteUrl ?: incoming.websiteUrl,
            links = (base.links + incoming.links).distinctBy { it.url },
            source = listOf(base.source, incoming.source).filter { it.isNotBlank() }
                .distinct().joinToString(",")
        )
    }
}
