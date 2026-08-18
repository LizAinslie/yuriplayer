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

/** Direct Wikipedia / Commons image + summary bio. Music artists only. */
class WikipediaArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikipedia"
    override val displayName: String = "Wikipedia"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val titles = searchTitles(artistName)
            for (title in titles.take(5)) {
                if (!ArtistNameMatch.looksLike(artistName, title)) continue
                val summary = pageSummary(title) ?: continue
                if (summary.optString("type") == "disambiguation") continue
                val description = summary.optString("description")
                if (!isMusicDescription(description) && !isMusicExtract(summary.optString("extract"))) {
                    continue
                }
                // Title on the page must still look like the artist (redirect safety)
                val pageTitle = summary.optString("title").ifBlank { title }
                if (!ArtistNameMatch.looksLike(artistName, pageTitle)) continue

                val extract = summary.optString("extract").takeIf { it.isNotBlank() } ?: continue
                val pageUrl = summary.optJSONObject("content_urls")
                    ?.optJSONObject("desktop")
                    ?.optString("page")
                return@withContext ArtistProfile(
                    artistKey = key,
                    displayName = pageTitle.ifBlank { artistName.trim() },
                    bio = extract,
                    links = listOfNotNull(
                        pageUrl?.takeIf { it.isNotBlank() }?.let { categorizeLink(it, "Wikipedia") }
                    ),
                    source = id
                )
            }
            null
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val titles = searchTitles(artistName)
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (title in titles.take(5)) {
            if (!ArtistNameMatch.looksLike(artistName, title)) continue
            val summary = pageSummary(title)
            val pageTitle = summary?.optString("title")?.ifBlank { title } ?: title
            if (!ArtistNameMatch.looksLike(artistName, pageTitle)) continue
            if (summary != null && summary.optString("type") == "disambiguation") continue
            val desc = summary?.optString("description").orEmpty()
            if (summary != null &&
                !isMusicDescription(desc) &&
                !isMusicExtract(summary.optString("extract"))
            ) {
                // Still allow images if the title matched tightly and has a pageimage
                // only when description is empty (new/stub pages)
                if (desc.isNotBlank()) continue
            }
            summaryImages(summary, pageTitle).forEach { (url, label) ->
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) out[fp] = ArtistImageCandidate(url, id, label)
            }
            pageImages(title).forEach { url ->
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) out[fp] = ArtistImageCandidate(url, id, "Wikipedia · $pageTitle")
            }
        }
        out.values.toList()
    }

    private fun isMusicDescription(description: String): Boolean {
        if (description.isBlank()) return false
        val d = description.lowercase()
        if (NON_MUSIC_HINTS.any { d.contains(it) }) return false
        return MUSIC_HINTS.any { d.contains(it) }
    }

    private fun isMusicExtract(extract: String): Boolean {
        if (extract.isBlank()) return false
        val head = extract.take(400).lowercase()
        return MUSIC_HINTS.any { head.contains(it) }
    }

    private suspend fun searchTitles(name: String): List<String> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url =
            "https://en.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=8&namespace=0&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            if (arr.length() < 2) return emptyList()
            val titles = arr.getJSONArray(1)
            buildList {
                for (i in 0 until titles.length()) {
                    titles.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.sortedByDescending { ArtistNameMatch.score(name, it) }
                .filter { ArtistNameMatch.score(name, it) >= 70 }
        } catch (e: Exception) {
            Log.w(TAG, "opensearch failed", e)
            emptyList()
        }
    }

    private suspend fun pageSummary(title: String): JSONObject? {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val body = get("https://en.wikipedia.org/api/rest_v1/page/summary/$path") ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun summaryImages(
        root: JSONObject?,
        title: String
    ): List<Pair<String, String>> {
        if (root == null) return emptyList()
        return buildList {
            root.optJSONObject("originalimage")?.optString("source")
                ?.takeIf { it.startsWith("http") }
                ?.let { add(it to "Wikipedia original · $title") }
            root.optJSONObject("thumbnail")?.optString("source")
                ?.takeIf { it.startsWith("http") }
                ?.let { add(it to "Wikipedia thumb · $title") }
        }
    }

    private suspend fun pageImages(title: String): List<String> {
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url =
            "https://en.wikipedia.org/w/api.php?action=query&titles=$q" +
                "&prop=pageimages&pithumbsize=1000&pilimit=5&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val pages = JSONObject(body)
                .optJSONObject("query")
                ?.optJSONObject("pages")
                ?: return emptyList()
            val out = mutableListOf<String>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                page.optJSONObject("thumbnail")?.optString("source")
                    ?.takeIf { it.startsWith("http") }
                    ?.let { out += it }
                page.optJSONObject("original")?.optString("source")
                    ?.takeIf { it.startsWith("http") }
                    ?.let { out += it }
            }
            // Prefer original-style (larger) by putting them first — fingerprint dedupes later
            out.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "pageImages failed", e)
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
        private const val TAG = "WikiArtistImg"

        private val MUSIC_HINTS = listOf(
            "musician", "singer", "rapper", "band", "musical group", "songwriter",
            "composer", "dj", "disc jockey", "vocalist", "guitarist", "drummer",
            "record producer", "hip hop", "pop group", "rock band", "ensemble",
            "duo", "trio", "boy band", "girl group", "music artist", "recording artist"
        )

        private val NON_MUSIC_HINTS = listOf(
            "politician", "footballer", "soccer", "actor", "actress", "author",
            "novelist", "scientist", "physicist", "chemist", "mathematician",
            "businessman", "entrepreneur", "athlete", "olympic", "basketball",
            "cricketer", "tennis", "philosopher", "historian"
        )
    }
}
