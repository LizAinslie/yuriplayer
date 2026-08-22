package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Wikipedia artist pages only — never albums, songs, EPs, or other works.
 * "Lil' Darlin'" (1959 album) must not match query "Lil Darkie".
 */
class WikipediaArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "wikipedia"
    override val displayName: String = "Wikipedia"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            for (title in searchArtistTitles(artistName)) {
                val summary = pageSummary(title) ?: continue
                if (!isArtistPage(summary, artistName)) continue

                val pageTitle = summary.optString("title").ifBlank { title }
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
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (title in searchArtistTitles(artistName)) {
            val summary = pageSummary(title) ?: continue
            if (!isArtistPage(summary, artistName)) continue
            val pageTitle = summary.optString("title").ifBlank { title }

            summaryImages(summary, pageTitle).forEach { (url, label) ->
                if (url.isBlank()) return@forEach
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) out[fp] = ArtistImageCandidate(url, id, label)
            }
            pageImages(title).forEach { url ->
                if (url.isBlank()) return@forEach
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) out[fp] = ArtistImageCandidate(url, id, "Wikipedia · $pageTitle")
            }
        }
        out.values.toList()
    }

    /**
     * True only for person / musical-group pages whose title matches the artist
     * and whose description is not a work (album, song, single, …).
     */
    private fun isArtistPage(summary: JSONObject, artistName: String): Boolean {
        if (summary.optString("type") == "disambiguation") return false

        val pageTitle = summary.optString("title")
        if (pageTitle.isBlank() || !ArtistNameMatch.looksLike(artistName, pageTitle)) return false

        val description = summary.optString("description")
        val extract = summary.optString("extract")

        // Hard reject works / non-artists
        if (isWorkDescription(description) || isWorkExtract(extract)) return false

        // Must look like a person or group (musician, rapper, band, …)
        if (!isArtistDescription(description) && !isArtistExtract(extract)) return false

        // Italic displaytitle is Wikipedia's usual album/song markup — reject
        val display = summary.optString("displaytitle")
        if (display.contains("<i>", ignoreCase = true) ||
            display.contains("<em>", ignoreCase = true)
        ) {
            // Allow only if description still clearly says musician/band
            if (!isArtistDescription(description)) return false
        }

        return true
    }

    private fun isWorkDescription(description: String): Boolean {
        if (description.isBlank()) return false
        val d = description.lowercase()
        return WORK_HINTS.any { d.contains(it) }
    }

    private fun isWorkExtract(extract: String): Boolean {
        if (extract.isBlank()) return false
        val head = extract.take(280).lowercase()
        // "… is a 1959 live album …" / "… is a song by …"
        return WORK_HINTS.any { head.contains(it) }
    }

    private fun isArtistDescription(description: String): Boolean {
        if (description.isBlank()) return false
        val d = description.lowercase()
        if (WORK_HINTS.any { d.contains(it) }) return false
        if (NON_ARTIST_HINTS.any { d.contains(it) }) return false
        return ARTIST_HINTS.any { d.contains(it) }
    }

    private fun isArtistExtract(extract: String): Boolean {
        if (extract.isBlank()) return false
        val head = extract.take(400).lowercase()
        if (WORK_HINTS.any { head.contains(it) }) return false
        return ARTIST_HINTS.any { head.contains(it) }
    }

    /**
     * Prefer queries that bias toward people/groups, then strict name + artist filter.
     */
    private suspend fun searchArtistTitles(name: String): List<String> {
        val trimmed = name.trim()
        val queries = listOf(
            "$trimmed musician",
            "$trimmed rapper",
            "$trimmed singer",
            "$trimmed band",
            trimmed
        )
        val seen = LinkedHashSet<String>()
        for (qRaw in queries) {
            val requestUrl = url("https://en.wikipedia.org") {
                path("w", "api.php")
                param("action", "query")
                param("list", "search")
                param("srsearch", qRaw)
                param("srnamespace", 0)
                param("srlimit", 8)
                param("format", "json")
            }
            val body = get(requestUrl) ?: continue
            try {
                val arr = JSONObject(body)
                    .optJSONObject("query")
                    ?.optJSONArray("search")
                    ?: continue
                for (i in 0 until arr.length()) {
                    val title = arr.optJSONObject(i)?.optString("title") ?: continue
                    if (title.isBlank()) continue
                    // Snippet often reveals album vs person early
                    val snippet = arr.optJSONObject(i)?.optString("snippet").orEmpty()
                        .replace(Regex("<[^>]+>"), "")
                        .lowercase()
                    if (WORK_HINTS.any { snippet.contains(it) }) continue
                    if (ArtistNameMatch.looksLike(trimmed, title)) {
                        seen += title
                    }
                }
            } catch (e: Exception) {
                log.w(e) { "search failed for $qRaw" }
            }
            if (seen.size >= 6) break
        }
        return seen.sortedByDescending { ArtistNameMatch.score(trimmed, it) }
    }

    private suspend fun pageSummary(title: String): JSONObject? {
        val requestUrl = url("https://en.wikipedia.org") {
            path("api", "rest_v1", "page", "summary", title.replace(' ', '_'), encodeSlash = true)
        }
        val body = get(requestUrl) ?: return null
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
        val requestUrl = url("https://en.wikipedia.org") {
            path("w", "api.php")
            param("action", "query")
            param("titles", title)
            param("prop", "pageimages")
            param("pithumbsize", 1000)
            param("pilimit", 5)
            param("format", "json")
        }
        val body = get(requestUrl) ?: return emptyList()
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
            out.distinct()
        } catch (e: Exception) {
            log.w(e) { "pageImages failed" }
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
        private val log = yuriLog("WikiArtistImg")

        /** Person / group signals. */
        private val ARTIST_HINTS = listOf(
            "musician", "singer", "rapper", "band", "musical group", "songwriter",
            "composer", "dj", "disc jockey", "vocalist", "guitarist", "drummer",
            "record producer", "hip hop", "pop group", "rock band", "ensemble",
            "duo", "trio", "boy band", "girl group", "music artist", "recording artist",
            "american rapper", "british singer", "multi-instrumentalist"
        )

        /** Works — albums, songs, etc. Must never be treated as the artist. */
        private val WORK_HINTS = listOf(
            "album", "studio album", "live album", "debut album", "extended play",
            " ep", "ep ", "single", "song by", "song from", "composition",
            "soundtrack", "compilation", "mixtape", "discography", "track from",
            "musical work", "opera", "symphony", "concerto", "film score"
        )

        private val NON_ARTIST_HINTS = listOf(
            "politician", "footballer", "soccer", "actor", "actress", "author",
            "novelist", "scientist", "physicist", "chemist", "mathematician",
            "businessman", "entrepreneur", "athlete", "olympic", "basketball",
            "cricketer", "tennis", "philosopher", "historian"
        )
    }
}
