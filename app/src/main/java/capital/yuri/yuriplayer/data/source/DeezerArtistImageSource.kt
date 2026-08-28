package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Deezer public search API — no key required. One highest-res image per matched artist. */
class DeezerArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "deezer"
    override val displayName: String = "Deezer"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = null

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        if (kind == ArtistImageKind.BANNER) return@withContext emptyList()
        val requestUrl = url("https://api.deezer.com") {
            path("search", "artist")
            param("q", artistName.trim())
            param("limit", 8)
        }
        val body = get(requestUrl)
            ?: return@withContext emptyList()
        try {
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()

            data class Hit(
                val name: String,
                val url: String,
                val size: Int,
                val fans: Int,
                val artistId: Long
            )
            val hits = ArrayList<Hit>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.isBlank()) continue
                if (!ArtistNameMatch.looksLike(artistName, name)) continue
                val artistId = a.optLong("id", 0L)
                val fans = a.optInt("nb_fan", 0)
                val ranked = listOf(
                    a.optString("picture_xl") to 1000,
                    a.optString("picture_big") to 500,
                    a.optString("picture_medium") to 250
                )
                val best = ranked.firstOrNull { (u, _) ->
                    u.isNotBlank() &&
                        !u.contains("artist-default") &&
                        !u.contains("/images/artist//")
                } ?: continue
                hits.add(Hit(name, best.first, best.second, fans, artistId))
            }

            val byFp = LinkedHashMap<String, Hit>()
            for (hit in hits.sortedByDescending { it.fans }) {
                val fp = ArtistNameMatch.imageFingerprint(hit.url)
                val existing = byFp[fp]
                if (existing == null || hit.size > existing.size ||
                    (hit.size == existing.size && hit.fans > existing.fans)
                ) {
                    byFp[fp] = hit
                }
            }

            val byName = LinkedHashMap<String, Hit>()
            for (hit in byFp.values.sortedByDescending { it.fans }) {
                val nk = ArtistNameMatch.normalize(hit.name)
                val existing = byName[nk]
                if (existing == null || hit.fans > existing.fans ||
                    (hit.fans == existing.fans && hit.size > existing.size)
                ) {
                    byName[nk] = hit
                }
            }

            byName.values.map { hit ->
                ArtistImageCandidate(
                    url = hit.url,
                    sourceId = id,
                    label = "Deezer · ${hit.name}",
                    width = hit.size,
                    height = hit.size
                )
            }
        } catch (e: Exception) {
            log.w(e) { "parse failed" }
            emptyList()
        }
    }

    private suspend fun get(url: String): String? {
        return try {
            val response = http.get(url)
            if (!response.status.isSuccess()) null else response.bodyAsText()
        } catch (e: Exception) {
            log.w(e) { "GET $url" }
            null
        }
    }

    companion object {
        private val log = yuriLog("DeezerArtistImg")
    }
}
