package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import kotlin.time.Duration.Companion.milliseconds

/**
 * MusicBrainz + Cover Art Archive + light Wikidata/Wikipedia helpers via shared Ktor [HttpClient].
 *
 * Kept hand-rolled (not eAlvaBrainz): that library last shipped 2022 on Retrofit/Moshi and would
 * duplicate HTTP stacks. We keep MB's ~1.1s throttle here so auto-enrich stays polite.
 */
class MusicBrainzClient(
    private val http: HttpClient
) {

    data class ReleaseHit(
        val mbid: String,
        val title: String?,
        val year: Int?,
        val hasFrontCover: Boolean,
        val genres: List<String> = emptyList()
    )

    data class ArtistHit(
        val mbid: String,
        val name: String,
        val imageUrl: String? = null,
        val website: String? = null,
        val links: List<ArtistLink> = emptyList(),
        val genres: List<String> = emptyList()
    )

    private val rateLock = Mutex()
    private var lastRequestAt = 0L

    /**
     * @param includeTags when true, issues a second MB request for release tags (genres).
     *   Auto year/cover fill should pass false — halves rate-limited traffic.
     */
    suspend fun searchRelease(
        artist: String?,
        album: String?,
        includeTags: Boolean = true
    ): ReleaseHit? =
        withContext(Dispatchers.IO) {
            if (album.isNullOrBlank()) return@withContext null
            val query = buildString {
                append("release:\"")
                append(escapeLucene(album.trim()))
                append("\"")
                if (!artist.isNullOrBlank()) {
                    append(" AND artist:\"")
                    append(escapeLucene(artist.trim()))
                    append("\"")
                }
            }
            val requestUrl = url("https://musicbrainz.org") {
                path("ws", "2", "release")
                param("query", query)
                param("fmt", "json")
                param("limit", 5)
            }
            val body = getText(requestUrl) ?: return@withContext null
            val basic = parseReleaseSearch(body) ?: return@withContext null
            if (!includeTags) return@withContext basic
            val detail = getText(
                url("https://musicbrainz.org") {
                    path("ws", "2", "release", basic.mbid)
                    param("inc", "tags")
                    param("fmt", "json")
                }
            )
            val genres = detail?.let { parseTags(it) }.orEmpty()
            basic.copy(genres = genres)
        }

    suspend fun lookupArtist(mbid: String): ArtistHit? =
        withContext(Dispatchers.IO) {
            if (mbid.isBlank()) return@withContext null
            val detailUrl = url("https://musicbrainz.org") {
                path("ws", "2", "artist", mbid)
                param("inc", "url-rels+tags")
                param("fmt", "json")
            }
            val detail = getText(detailUrl) ?: return@withContext ArtistHit(mbid, mbid)
            val hit = parseArtistDetail(detail, mbid)
            val image = hit.imageUrl
                ?: resolveWikidataImage(hit)
                ?: resolveWikipediaImage(hit)
            hit.copy(imageUrl = image)
        }

    suspend fun searchArtist(name: String): ArtistHit? =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext null
            val trimmed = name.trim()
            val mbid = findBestArtistMbid(trimmed) ?: return@withContext null
            lookupArtist(mbid) ?: ArtistHit(mbid, trimmed)
        }

    suspend fun expandImageCandidates(hit: ArtistHit): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            hit.imageUrl?.let { out[it] = "MusicBrainz image" }
            val wdUrl = hit.links.firstOrNull { it.url.contains("wikidata.org", true) }?.url
            if (wdUrl != null) {
                val qid = Regex("Q\\d+").find(wdUrl)?.value
                if (qid != null) {
                    allWikidataP18(qid).forEachIndexed { i, url ->
                        if (url !in out) out[url] = "Wikidata P18${if (i > 0) " #${i + 1}" else ""}"
                    }
                }
            }
            resolveWikipediaImage(hit)?.let { if (it !in out) out[it] = "Wikipedia (via MB)" }
            hit.links.filter {
                it.url.contains("upload.wikimedia.org", true) ||
                    it.url.contains("commons.wikimedia.org", true)
            }.forEach { if (it.url !in out) out[it.url] = it.label }
            out.entries.map { it.key to it.value }
        }

    private suspend fun allWikidataP18(qid: String): List<String> {
        val api = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbgetentities")
            param("ids", qid)
            param("props", "claims")
            param("format", "json")
        }
        val body = getText(api) ?: return emptyList()
        return try {
            val p18 = JSONObject(body)
                .optJSONObject("entities")
                ?.optJSONObject(qid)
                ?.optJSONObject("claims")
                ?.optJSONArray("P18")
                ?: return emptyList()
            buildList {
                for (i in 0 until p18.length()) {
                    val fileName = p18.optJSONObject(i)
                        ?.optJSONObject("mainsnak")
                        ?.optJSONObject("datavalue")
                        ?.optString("value")
                        ?.takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        url("https://commons.wikimedia.org") {
                            path("wiki", "Special:FilePath", fileName.replace(' ', '_'), encodeSlash = true)
                            param("width", 1000)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "allWikidataP18 $qid", e)
            emptyList()
        }
    }

    private suspend fun findBestArtistMbid(name: String): String? {
        val queries = listOf(
            "artist:\"${escapeLucene(name)}\"",
            escapeLucene(name)
        )
        for (q in queries) {
            val requestUrl = url("https://musicbrainz.org") {
                path("ws", "2", "artist")
                param("query", q)
                param("fmt", "json")
                param("limit", 10)
            }
            val body = getText(requestUrl) ?: continue
            val mbid = pickBestArtistMbid(body, name)
            if (mbid != null) return mbid
        }
        return null
    }

    private fun pickBestArtistMbid(json: String, wanted: String): String? {
        return try {
            val root = JSONObject(json)
            val artists = root.optJSONArray("artists") ?: return null
            if (artists.length() == 0) return null
            var exactId: String? = null
            var exactScore = -1
            var bestId: String? = null
            var bestScore = -1
            for (i in 0 until artists.length()) {
                val a = artists.optJSONObject(i) ?: continue
                val id = a.optString("id").takeIf { it.isNotBlank() } ?: continue
                val n = a.optString("name").trim()
                val score = a.optInt("score", 0)
                val aliases = a.optJSONArray("aliases")
                val nameMatch = n.equals(wanted, ignoreCase = true)
                val aliasMatch = aliases != null && (0 until aliases.length()).any { ai ->
                    aliases.optJSONObject(ai)?.optString("name")
                        ?.equals(wanted, ignoreCase = true) == true
                }
                if (nameMatch || aliasMatch) {
                    if (score >= exactScore) {
                        exactScore = score
                        exactId = id
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            when {
                exactId != null -> exactId
                bestScore >= 80 -> bestId
                else -> exactId ?: bestId?.takeIf { bestScore >= 50 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pickBestArtistMbid failed", e)
            null
        }
    }

    private suspend fun resolveWikidataImage(hit: ArtistHit): String? {
        val wdUrl = hit.links.firstOrNull { it.url.contains("wikidata.org", true) }?.url
            ?: return null
        val qid = Regex("Q\\d+").find(wdUrl)?.value ?: return null
        return allWikidataP18(qid).firstOrNull()
    }

    private suspend fun resolveWikipediaImage(hit: ArtistHit): String? {
        val wikiUrl = hit.links.firstOrNull {
            it.url.contains("wikipedia.org", ignoreCase = true)
        }?.url ?: return null
        val encodedPath = wikiUrl.substringAfter("/wiki/").takeIf { it.isNotBlank() } ?: return null
        val page = URLDecoder.decode(encodedPath, "UTF-8")
        val lang = Regex("https?://([a-z]{2,3})\\.wikipedia").find(wikiUrl)?.groupValues?.getOrNull(1)
            ?: "en"
        val api = url("https://$lang.wikipedia.org") {
            path("api", "rest_v1", "page", "summary", page.replace(' ', '_'), encodeSlash = true)
        }
        val body = getText(api) ?: return null
        return try {
            val root = JSONObject(body)
            root.optJSONObject("originalimage")?.optString("source")?.takeIf { it.isNotBlank() }
                ?: root.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia image failed", e)
            null
        }
    }

    suspend fun downloadFrontCover(mbid: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val urls = listOf(
                url("https://coverartarchive.org") { path("release", mbid, "front-500") },
                url("https://coverartarchive.org") { path("release", mbid, "front") }
            )
            for (u in urls) {
                if (downloadToFile(u, destFile)) return@withContext true
            }
            false
        }

    suspend fun downloadUrl(url: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) { downloadToFile(url, destFile) }

    private fun parseTags(json: String): List<String> {
        return try {
            val tags = JSONObject(json).optJSONArray("tags") ?: return emptyList()
            val scored = mutableListOf<Pair<String, Int>>()
            for (i in 0 until tags.length()) {
                val t = tags.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                scored += name to t.optInt("count", 0)
            }
            scored.sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.lowercase() }
                .take(12)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseArtistDetail(json: String, mbid: String): ArtistHit {
        return try {
            val root = JSONObject(json)
            val name = root.optString("name").ifBlank { "" }
            val relations = root.optJSONArray("relations")
            var imageUrl: String? = null
            var website: String? = null
            val links = mutableListOf<ArtistLink>()

            if (relations != null) {
                for (i in 0 until relations.length()) {
                    val rel = relations.optJSONObject(i) ?: continue
                    val type = rel.optString("type").lowercase()
                    val urlObj = rel.optJSONObject("url") ?: continue
                    val resource = urlObj.optString("resource").takeIf { it.isNotBlank() } ?: continue

                    when {
                        type.contains("image") || type == "picture" -> {
                            if (imageUrl == null) imageUrl = resource
                        }
                        type == "official homepage" || type == "homepage" || type == "website" -> {
                            if (website == null) website = resource
                            links += categorizeLink(resource, "Website")
                        }
                        type.contains("twitter") || resource.contains("twitter.com") ||
                            resource.contains("x.com/") ->
                            links += categorizeLink(resource, "X / Twitter")
                        type.contains("instagram") || resource.contains("instagram.com") ->
                            links += categorizeLink(resource, "Instagram")
                        type.contains("facebook") || resource.contains("facebook.com") ->
                            links += categorizeLink(resource, "Facebook")
                        type.contains("tiktok") || resource.contains("tiktok.com") ->
                            links += categorizeLink(resource, "TikTok")
                        type.contains("bandcamp") || resource.contains("bandcamp.com") ->
                            links += categorizeLink(resource, "Bandcamp")
                        type.contains("soundcloud") || resource.contains("soundcloud.com") ->
                            links += categorizeLink(resource, "SoundCloud")
                        type.contains("spotify") || resource.contains("spotify.com") ->
                            links += categorizeLink(resource, "Spotify")
                        type.contains("youtube") || resource.contains("youtube.com") ||
                            resource.contains("youtu.be") ->
                            links += categorizeLink(resource, "YouTube")
                        type.contains("itunes") || type.contains("apple") ||
                            resource.contains("music.apple.com") ->
                            links += categorizeLink(resource, "Apple Music")
                        type.contains("deezer") || resource.contains("deezer.com") ->
                            links += categorizeLink(resource, "Deezer")
                        type.contains("tidal") || resource.contains("tidal.com") ->
                            links += categorizeLink(resource, "Tidal")
                        type == "wikidata" || resource.contains("wikidata.org") ->
                            links += categorizeLink(resource, "Wikidata")
                        type.contains("wikipedia") || resource.contains("wikipedia.org") ->
                            links += categorizeLink(resource, "Wikipedia")
                        type.contains("discogs") || resource.contains("discogs.com") ->
                            links += categorizeLink(resource, "Discogs")
                        type.contains("last.fm") || type.contains("lastfm") ||
                            resource.contains("last.fm") ->
                            links += categorizeLink(resource, "Last.fm")
                        type.contains("allmusic") ->
                            links += categorizeLink(resource, "AllMusic")
                        type.contains("songkick") ->
                            links += categorizeLink(resource, "Songkick")
                        type.contains("bandsintown") ->
                            links += categorizeLink(resource, "Bandsintown")
                        else -> {
                            if (resource.startsWith("http")) {
                                links += categorizeLink(resource, type.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }

            ArtistHit(
                mbid = mbid,
                name = name,
                imageUrl = imageUrl,
                website = website,
                links = links.distinctBy { it.url.lowercase() }.take(24),
                genres = parseTags(json)
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse artist detail failed", e)
            ArtistHit(mbid, "")
        }
    }

    private fun parseReleaseSearch(json: String): ReleaseHit? {
        return try {
            val root = JSONObject(json)
            val releases = root.optJSONArray("releases") ?: return null
            if (releases.length() == 0) return null
            var best: ReleaseHit? = null
            for (i in 0 until releases.length()) {
                val r = releases.optJSONObject(i) ?: continue
                val mbid = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = r.optString("title").takeIf { it.isNotBlank() }
                val date = r.optString("date").takeIf { it.isNotBlank() }
                val year = date?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2100 }
                val caa = r.optJSONObject("cover-art-archive")
                val hasFront = caa?.optBoolean("front") == true
                val hit = ReleaseHit(mbid, title, year, hasFront)
                if (best == null) best = hit
                else if (best.year == null && year != null) best = hit
                else if (!best.hasFrontCover && hasFront) best = hit
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "parse release search failed", e)
            null
        }
    }

    private suspend fun getText(url: String): String? = rateLock.withLock {
        throttle()
        try {
            val response = http.get(url)
            if (!response.status.isSuccess()) {
                Log.w(TAG, "GET $url → ${response.status}")
                return@withLock null
            }
            response.bodyAsText()
        } catch (e: Exception) {
            Log.w(TAG, "GET failed $url", e)
            null
        }
    }

    private suspend fun downloadToFile(url: String, dest: File): Boolean = rateLock.withLock {
        // CAA is separate from MB and does not need the same strict throttle,
        // but sharing the lock keeps total outbound polite under auto-enrich.
        throttle()
        try {
            http.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                response.bodyAsChannel().toInputStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                dest.isFile && dest.length() > 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed $url", e)
            false
        }
    }

    private suspend fun throttle() {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait.milliseconds)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun escapeLucene(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("(", "\\(")
            .replace(")", "\\)")

    companion object {
        private const val TAG = "MusicBrainz"
        /** Anonymous MB guideline ≈ 1 req/s. */
        private const val MIN_INTERVAL_MS = 1_100L
    }
}
