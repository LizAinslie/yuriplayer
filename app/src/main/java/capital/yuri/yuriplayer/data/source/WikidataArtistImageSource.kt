package capital.yuri.yuriplayer.data.source

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Wikidata entity search → all P18 (image) claims. */
class WikidataArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = null

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val qids = searchEntities(artistName).take(3)
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (qid in qids) {
            p18Images(qid).forEach { url ->
                if (url !in out) {
                    out[url] = ArtistImageCandidate(url, id, "Wikidata $qid")
                }
            }
        }
        out.values.toList()
    }

    private suspend fun searchEntities(name: String): List<String> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url =
            "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                "&search=$q&language=en&type=item&limit=6&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("search") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.startsWith("Q") } ?: continue
                    // Prefer human / musical group-ish descriptions when present
                    add(id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            emptyList()
        }
    }

    private suspend fun p18Images(qid: String): List<String> {
        val url =
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid" +
                "&props=claims&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val claims = JSONObject(body)
                .optJSONObject("entities")
                ?.optJSONObject(qid)
                ?.optJSONObject("claims")
                ?: return emptyList()
            val p18 = claims.optJSONArray("P18") ?: return emptyList()
            buildList {
                for (i in 0 until p18.length()) {
                    val fileName = p18.optJSONObject(i)
                        ?.optJSONObject("mainsnak")
                        ?.optJSONObject("datavalue")
                        ?.optString("value")
                        ?.takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                            URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8") +
                            "?width=1000"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P18 failed for $qid", e)
            emptyList()
        }
    }

    private suspend fun get(url: String): String? {
        return try {
            val response = http.get(url)
            if (!response.status.isSuccess()) null else response.bodyAsText()
        } catch (e: Exception) {
            Log.w(TAG, "GET $url", e)
            null
        }
    }

    companion object {
        private const val TAG = "WikidataArtistImg"
    }
}
