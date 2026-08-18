package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Wikidata entity search → P18 images + P136 genres. */
class WikidataArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val qid = searchEntities(artistName).firstOrNull() ?: return@withContext null
            val genres = genreLabels(qid)
            val social = socialLinks(qid)
            if (genres.isEmpty() && social.isEmpty()) {
                return@withContext ArtistProfile(
                    artistKey = key,
                    displayName = artistName.trim(),
                    links = listOf(categorizeLink("https://www.wikidata.org/wiki/$qid", "Wikidata")),
                    source = id
                )
            }
            ArtistProfile(
                artistKey = key,
                displayName = artistName.trim(),
                links = listOf(categorizeLink("https://www.wikidata.org/wiki/$qid", "Wikidata")) + social,
                genres = genres,
                source = id
            )
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val qids = searchEntities(artistName).take(3)
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (qid in qids) {
            p18Images(qid).forEach { url ->
                if (url !in out) out[url] = ArtistImageCandidate(url, id, "Wikidata $qid")
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
                    o.optString("id").takeIf { it.startsWith("Q") }?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            emptyList()
        }
    }

    private suspend fun p18Images(qid: String): List<String> {
        val claims = entityClaims(qid) ?: return emptyList()
        val p18 = claims.optJSONArray("P18") ?: return emptyList()
        return buildList {
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
    }

    /** P136 = genre */
    private suspend fun genreLabels(qid: String): List<String> {
        val claims = entityClaims(qid) ?: return emptyList()
        val p136 = claims.optJSONArray("P136") ?: return emptyList()
        val ids = buildList {
            for (i in 0 until p136.length()) {
                val id = p136.optJSONObject(i)
                    ?.optJSONObject("mainsnak")
                    ?.optJSONObject("datavalue")
                    ?.optJSONObject("value")
                    ?.optString("id")
                    ?.takeIf { it.startsWith("Q") }
                    ?: continue
                add(id)
            }
        }.take(10)
        if (ids.isEmpty()) return emptyList()
        return entityLabels(ids)
    }

    /** Official website P856, Twitter P2002, Instagram P2003, Facebook P2013, YouTube P2397 */
    private suspend fun socialLinks(qid: String): List<ArtistLink> {
        val claims = entityClaims(qid) ?: return emptyList()
        val out = mutableListOf<ArtistLink>()

        fun firstString(prop: String): String? {
            val arr = claims.optJSONArray(prop) ?: return null
            return arr.optJSONObject(0)
                ?.optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")
                ?.optString("value")
                ?.takeIf { it.isNotBlank() }
        }

        firstString("P856")?.let { url ->
            out += categorizeLink(if (url.startsWith("http")) url else "https://$url", "Website")
        }
        firstString("P2002")?.let { handle ->
            out += categorizeLink("https://x.com/$handle", "X / Twitter")
        }
        firstString("P2003")?.let { handle ->
            out += categorizeLink("https://www.instagram.com/$handle", "Instagram")
        }
        firstString("P2013")?.let { handle ->
            out += categorizeLink("https://www.facebook.com/$handle", "Facebook")
        }
        firstString("P2397")?.let { channel ->
            // YouTube channel id
            out += categorizeLink("https://www.youtube.com/channel/$channel", "YouTube")
        }
        firstString("P3040")?.let { handle ->
            out += categorizeLink("https://soundcloud.com/$handle", "SoundCloud")
        }
        return out.distinctBy { it.url.lowercase() }
    }

    private suspend fun entityClaims(qid: String): JSONObject? {
        val url =
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid" +
                "&props=claims&format=json"
        val body = get(url) ?: return null
        return try {
            JSONObject(body)
                .optJSONObject("entities")
                ?.optJSONObject(qid)
                ?.optJSONObject("claims")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun entityLabels(ids: List<String>): List<String> {
        val joined = ids.joinToString("|")
        val url =
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$joined" +
                "&props=labels&languages=en&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val entities = JSONObject(body).optJSONObject("entities") ?: return emptyList()
            ids.mapNotNull { id ->
                entities.optJSONObject(id)
                    ?.optJSONObject("labels")
                    ?.optJSONObject("en")
                    ?.optString("value")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "labels failed", e)
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
