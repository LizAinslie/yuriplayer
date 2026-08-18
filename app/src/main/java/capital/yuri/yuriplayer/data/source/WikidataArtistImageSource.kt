package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Wikidata entity search → P18 images + P136 genres. Only music artists. */
class WikidataArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val qid = searchMusicEntities(artistName).firstOrNull() ?: return@withContext null
            val genres = genreLabels(qid)
            val social = socialLinks(qid)
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
        val qids = searchMusicEntities(artistName).take(3)
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (qid in qids) {
            p18Images(qid).forEach { url ->
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) out[fp] = ArtistImageCandidate(url, id, "Wikidata $qid")
            }
        }
        out.values.toList()
    }

    private data class SearchHit(
        val id: String,
        val label: String,
        val description: String
    )

    private suspend fun searchMusicEntities(name: String): List<String> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url =
            "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                "&search=$q&language=en&type=item&limit=10&format=json"
        val body = get(url) ?: return emptyList()

        val candidates: List<SearchHit> = try {
            val arr: JSONArray? = JSONObject(body).optJSONArray("search")
            if (arr == null) {
                emptyList()
            } else {
                val hits = ArrayList<SearchHit>(arr.length())
                for (i in 0 until arr.length()) {
                    val o: JSONObject = arr.optJSONObject(i) ?: continue
                    val entityId = o.optString("id")
                    if (!entityId.startsWith("Q")) continue
                    hits.add(
                        SearchHit(
                            id = entityId,
                            label = o.optString("label"),
                            description = o.optString("description")
                        )
                    )
                }
                hits
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            emptyList()
        }

        if (candidates.isEmpty()) return emptyList()

        val wanted = name.trim()
        val scored = ArrayList<Pair<String, Int>>()
        for (hit in candidates) {
            if (!ArtistNameMatch.looksLike(wanted, hit.label)) continue
            val claims = entityClaims(hit.id) ?: continue
            if (!isMusicEntity(claims, hit.description)) continue
            scored.add(hit.id to ArtistNameMatch.score(wanted, hit.label))
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun isMusicEntity(claims: JSONObject, searchDescription: String): Boolean {
        if (MUSIC_DESC_HINTS.any { searchDescription.contains(it, ignoreCase = true) }) {
            return true
        }
        if (claims.optJSONArray("P136") != null) return true

        fun idsIn(prop: String): Set<String> {
            val arr = claims.optJSONArray(prop) ?: return emptySet()
            val ids = HashSet<String>()
            for (i in 0 until arr.length()) {
                val claim = arr.optJSONObject(i) ?: continue
                val mainsnak = claim.optJSONObject("mainsnak") ?: continue
                val datavalue = mainsnak.optJSONObject("datavalue") ?: continue
                val value = datavalue.optJSONObject("value") ?: continue
                val qid = value.optString("id")
                if (qid.startsWith("Q")) ids.add(qid)
            }
            return ids
        }

        val instanceOf = idsIn("P31")
        val occupation = idsIn("P106")
        return instanceOf.any { it in MUSIC_INSTANCE_QIDS } ||
            occupation.any { it in MUSIC_OCCUPATION_QIDS }
    }

    private suspend fun p18Images(qid: String): List<String> {
        val claims = entityClaims(qid) ?: return emptyList()
        val p18 = claims.optJSONArray("P18") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until p18.length()) {
            val claim = p18.optJSONObject(i) ?: continue
            val mainsnak = claim.optJSONObject("mainsnak") ?: continue
            val datavalue = mainsnak.optJSONObject("datavalue") ?: continue
            val fileName = datavalue.optString("value")
            if (fileName.isBlank()) continue
            val encoded = URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8").replace("+", "%20")
            out.add("https://commons.wikimedia.org/wiki/Special:FilePath/$encoded?width=1000")
        }
        return out
    }

    private suspend fun genreLabels(qid: String): List<String> {
        val claims = entityClaims(qid) ?: return emptyList()
        val p136 = claims.optJSONArray("P136") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until p136.length()) {
            if (ids.size >= 10) break
            val claim = p136.optJSONObject(i) ?: continue
            val mainsnak = claim.optJSONObject("mainsnak") ?: continue
            val datavalue = mainsnak.optJSONObject("datavalue") ?: continue
            val value = datavalue.optJSONObject("value") ?: continue
            val genreId = value.optString("id")
            if (genreId.startsWith("Q")) ids.add(genreId)
        }
        if (ids.isEmpty()) return emptyList()
        return entityLabels(ids)
    }

    private suspend fun socialLinks(qid: String): List<ArtistLink> {
        val claims = entityClaims(qid) ?: return emptyList()
        val out = mutableListOf<ArtistLink>()

        fun firstString(prop: String): String? {
            val arr = claims.optJSONArray(prop) ?: return null
            val claim = arr.optJSONObject(0) ?: return null
            val mainsnak = claim.optJSONObject("mainsnak") ?: return null
            val datavalue = mainsnak.optJSONObject("datavalue") ?: return null
            val value = datavalue.optString("value")
            return value.takeIf { it.isNotBlank() }
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
            out += categorizeLink("https://www.youtube.com/channel/$channel", "YouTube")
        }
        firstString("P3040")?.let { handle ->
            out += categorizeLink("https://soundcloud.com/$handle", "SoundCloud")
        }
        return out.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }
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

        private val MUSIC_INSTANCE_QIDS = setOf(
            "Q215380", "Q5741069", "Q1058743", "Q2088357", "Q588750",
            "Q13441638", "Q1135557", "Q253137", "Q2495704", "Q641226"
        )

        private val MUSIC_OCCUPATION_QIDS = setOf(
            "Q639669", "Q177220", "Q488205", "Q753110", "Q36834", "Q183945",
            "Q855091", "Q2252262", "Q130857", "Q55960555", "Q2494178",
            "Q15981151", "Q3282637"
        )

        private val MUSIC_DESC_HINTS = listOf(
            "musician", "singer", "rapper", "band", "musical group", "songwriter",
            "composer", "dj", "vocalist", "guitarist", "drummer", "record producer",
            "hip hop", "pop group", "rock band", "ensemble", "duo", "trio"
        )
    }
}
