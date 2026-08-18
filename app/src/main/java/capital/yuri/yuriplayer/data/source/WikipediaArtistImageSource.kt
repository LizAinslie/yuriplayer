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

/** Direct Wikipedia / Commons image + summary bio. */
class WikipediaArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikipedia"
    override val displayName: String = "Wikipedia"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val titles = searchTitles(artistName).ifEmpty {
                listOf(artistName.trim())
            }
            for (title in titles.take(3)) {
                val summary = pageSummary(title) ?: continue
                val extract = summary.optString("extract").takeIf {
                    it.isNotBlank() && summary.optString("type") != "disambiguation"
                } ?: continue
                val pageUrl = summary.optJSONObject("content_urls")
                    ?.optJSONObject("desktop")
                    ?.optString("page")
                return@withContext ArtistProfile(
                    artistKey = key,
                    displayName = summary.optString("title").ifBlank { artistName.trim() },
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
        val titles = searchTitles(artistName).ifEmpty {
            listOf(artistName.trim().replace(' ', '_'))
        }
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (title in titles.take(4)) {
            summaryImages(title).forEach { (url, label) ->
                if (url !in out) out[url] = ArtistImageCandidate(url, id, label)
            }
            pageImages(title).forEach { url ->
                if (url !in out) out[url] = ArtistImageCandidate(url, id, "Wikipedia · $title")
            }
        }
        out.values.toList()
    }

    private suspend fun searchTitles(name: String): List<String> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url =
            "https://en.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=5&namespace=0&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            if (arr.length() < 2) return emptyList()
            val titles = arr.getJSONArray(1)
            buildList {
                for (i in 0 until titles.length()) {
                    titles.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "opensearch failed", e)
            emptyList()
        }
    }

    private suspend fun pageSummary(title: String): JSONObject? {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val body = get("https://en.wikipedia.org/api/rest_v1/page/summary/$path") ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun summaryImages(title: String): List<Pair<String, String>> {
        val root = pageSummary(title) ?: return emptyList()
        return buildList {
            root.optJSONObject("originalimage")?.optString("source")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it to "Wikipedia original · $title") }
            root.optJSONObject("thumbnail")?.optString("source")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it to "Wikipedia thumb · $title") }
        }
    }

    private suspend fun pageImages(title: String): List<String> {
        val q = URLEncoder.encode(title, "UTF-8")
        val url =
            "https://en.wikipedia.org/w/api.php?action=query&titles=$q" +
                "&prop=pageimages|images&pithumbsize=1000&pilimit=5&imlimit=12&format=json"
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
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out += it }
                page.optJSONObject("original")?.optString("source")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out += it }
                val images = page.optJSONArray("images") ?: continue
                for (i in 0 until minOf(images.length(), 8)) {
                    val fname = images.optJSONObject(i)?.optString("title") ?: continue
                    if (!fname.startsWith("File:", ignoreCase = true)) continue
                    val lower = fname.lowercase()
                    if (lower.endsWith(".svg") || lower.endsWith(".gif")) continue
                    if (!lower.contains(".jpg") && !lower.contains(".jpeg") &&
                        !lower.contains(".png") && !lower.contains(".webp")
                    ) continue
                    val file = fname.removePrefix("File:").replace(' ', '_')
                    out += "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                        URLEncoder.encode(file, "UTF-8") + "?width=800"
                }
            }
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
    }
}
