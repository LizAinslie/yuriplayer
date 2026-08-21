package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.http.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Artist profile + images via [DiscogsClient]. */
class DiscogsArtistImageSource(
    private val discogs: DiscogsClient
) : ArtistInfoSource {

    override val id: String = "discogs"
    override val displayName: String = "Discogs"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val hit = discogs.searchArtists(artistName).firstOrNull() ?: return@withContext null
            val detail = discogs.artist(hit.id)
            val discogsUrl = url("https://www.discogs.com") {
                path("artist", hit.id.toString())
            }
            if (detail == null) {
                return@withContext ArtistProfile(
                    artistKey = key,
                    displayName = hit.title,
                    links = listOf(categorizeLink(discogsUrl, "Discogs")),
                    source = id
                )
            }
            val name = detail.optString("name").ifBlank { hit.title }
            val profile = DiscogsMarkup.resolve(detail.optString("profile").takeIf { it.isNotBlank() }, discogs)
            val urls = detail.optJSONArray("urls")
            val links = buildList {
                add(categorizeLink(discogsUrl, "Discogs"))
                if (urls != null) {
                    for (i in 0 until urls.length()) {
                        val u = urls.optString(i).takeIf { it.startsWith("http") } ?: continue
                        add(categorizeLink(u))
                    }
                }
            }.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }

            ArtistProfile(
                artistKey = key,
                displayName = name,
                bio = profile,
                links = links,
                source = id
            )
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val hits = discogs.searchArtists(artistName).take(2)
        if (hits.isEmpty()) return@withContext emptyList()

        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (hit in hits) {
            val images = discogs.artist(hit.id)?.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val img = images.optJSONObject(i) ?: continue
                    val type = img.optString("type")
                    val url = img.optString("uri").takeIf { it.startsWith("http") }
                        ?: img.optString("resource_url").takeIf { it.startsWith("http") }
                        ?: continue
                    val fp = ArtistNameMatch.imageFingerprint(url)
                    if (fp in out) continue
                    val w = img.optInt("width").takeIf { it > 0 }
                    val h = img.optInt("height").takeIf { it > 0 }
                    val label = buildString {
                        append("Discogs")
                        if (type.isNotBlank()) append(" · ").append(type)
                        append(" · ").append(hit.title)
                    }
                    out[fp] = ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = label,
                        width = w,
                        height = h
                    )
                }
            }
            listOfNotNull(hit.coverImage, hit.thumb).forEach { url ->
                if (!url.startsWith("http")) return@forEach
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) {
                    out[fp] = ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = "Discogs · ${hit.title}"
                    )
                }
            }
        }
        out.values.toList()
    }
}
