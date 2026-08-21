package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wikidata: music *artists* only (person/group with music occupation or instance-of).
 * Explicitly rejects albums, singles, songs, and other works (e.g. Lil' Darlin' Q6547361).
 */
class WikidataArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikidata"
    override val displayName: String = "Wikidata"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val qid = searchMusicArtists(artistName).firstOrNull() ?: return@withContext null
            val genres = genreLabels(qid)
            val social = socialLinks(qid)
            ArtistProfile(
                artistKey = key,
                displayName = artistName.trim(),
                links = listOf(
                    categorizeLink(
                        url("https://www.wikidata.org") { path("wiki", qid) },
                        "Wikidata"
                    )
                ) + social,
                genres = genres,
                source = id
            )
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val qids = searchMusicArtists(artistName).take(3)
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

    private suspend fun searchMusicArtists(name: String): List<String> {
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbsearchentities")
            param("search", name.trim())
            param("language", "en")
            param("type", "item")
            param("limit", 10)
            param("format", "json")
        }
        val body = get(requestUrl) ?: return emptyList()

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
                    val desc = o.optString("description")
                    // Fast reject works from search snippet
                    if (isWorkDescription(desc)) continue
                    hits.add(
                        SearchHit(
                            id = entityId,
                            label = o.optString("label"),
                            description = desc
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
            if (isWorkEntity(claims, hit.description)) continue
            if (!isMusicArtistEntity(claims, hit.description)) continue
            scored.add(hit.id to ArtistNameMatch.score(wanted, hit.label))
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun isWorkDescription(description: String): Boolean {
        if (description.isBlank()) return false
        val d = description.lowercase()
        return WORK_DESC_HINTS.any { d.contains(it) }
    }

    /** P31 in WORK_INSTANCE_QIDS → album/song/etc. */
    private fun isWorkEntity(claims: JSONObject, description: String): Boolean {
        if (isWorkDescription(description)) return true
        val instanceOf = idsIn(claims, "P31")
        return instanceOf.any { it in WORK_INSTANCE_QIDS }
    }

    private fun isMusicArtistEntity(claims: JSONObject, searchDescription: String): Boolean {
        if (isWorkDescription(searchDescription)) return false

        if (MUSIC_DESC_HINTS.any { searchDescription.contains(it, ignoreCase = true) }) {
            // Description says musician — still verify not also a work QID
            val instanceOf = idsIn(claims, "P31")
            if (instanceOf.any { it in WORK_INSTANCE_QIDS }) return false
            return true
        }

        // Genre on a *person* is fine; genre alone on a release is not — require person/group
        val instanceOf = idsIn(claims, "P31")
        val occupation = idsIn(claims, "P106")

        if (instanceOf.any { it in WORK_INSTANCE_QIDS }) return false

        val isPersonOrGroup = instanceOf.any { it in PERSON_OR_GROUP_QIDS } ||
            instanceOf.any { it in MUSIC_INSTANCE_QIDS }
        val hasMusicOccupation = occupation.any { it in MUSIC_OCCUPATION_QIDS }
        val hasGenre = claims.optJSONArray("P136") != null

        return when {
            instanceOf.any { it in MUSIC_INSTANCE_QIDS } -> true
            hasMusicOccupation && (isPersonOrGroup || instanceOf.isEmpty()) -> true
            hasGenre && isPersonOrGroup && hasMusicOccupation -> true
            else -> false
        }
    }

    private fun idsIn(claims: JSONObject, prop: String): Set<String> {
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
            out.add(
                url("https://commons.wikimedia.org") {
                    path("wiki", "Special:FilePath", fileName.replace(' ', '_'), encodeSlash = true)
                    param("width", 1000)
                }
            )
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

        firstString("P856")?.let { site ->
            out += categorizeLink(
                if (site.startsWith("http")) site else url("https://$site"),
                "Website"
            )
        }
        firstString("P2002")?.let { handle ->
            out += categorizeLink(url("https://x.com") { path(handle, encodeSlash = true) }, "X / Twitter")
        }
        firstString("P2003")?.let { handle ->
            out += categorizeLink(url("https://www.instagram.com") { path(handle, encodeSlash = true) }, "Instagram")
        }
        firstString("P2013")?.let { handle ->
            out += categorizeLink(url("https://www.facebook.com") { path(handle, encodeSlash = true) }, "Facebook")
        }
        firstString("P2397")?.let { channel ->
            out += categorizeLink(
                url("https://www.youtube.com") { path("channel", channel) },
                "YouTube"
            )
        }
        firstString("P3040")?.let { handle ->
            out += categorizeLink(url("https://soundcloud.com") { path(handle, encodeSlash = true) }, "SoundCloud")
        }
        return out.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }
    }

    private suspend fun entityClaims(qid: String): JSONObject? {
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbgetentities")
            param("ids", qid)
            param("props", "claims")
            param("format", "json")
        }
        val body = get(requestUrl) ?: return null
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
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbgetentities")
            param("ids", joined)
            param("props", "labels")
            param("languages", "en")
            param("format", "json")
        }
        val body = get(requestUrl) ?: return emptyList()
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

        private val PERSON_OR_GROUP_QIDS = setOf(
            "Q5",        // human
            "Q215380",   // musical group
            "Q2088357",  // musical ensemble
            "Q14514600"  // group of humans (sometimes used)
        )

        /** Albums, singles, songs, EPs, etc. — never treat as the artist. */
        private val WORK_INSTANCE_QIDS = setOf(
            "Q482994",   // album
            "Q208569",   // studio album
            "Q209939",   // live album
            "Q222910",   // compilation album
            "Q134556",   // single
            "Q7366",     // song
            "Q105543609", // musical work/composition
            "Q2188189",  // musical work
            "Q169930",   // extended play (EP)
            "Q108041408", // mixtape
            "Q4176708",  // soundtrack album
            "Q253137",   // (leave ensembles in MUSIC — not here)
            "Q182559",   // musical
            "Q11424"     // film (sometimes mislinked)
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

        private val WORK_DESC_HINTS = listOf(
            "album", "studio album", "live album", "single", "song", "extended play",
            "ep", "mixtape", "soundtrack", "composition", "musical work"
        )
    }
}
