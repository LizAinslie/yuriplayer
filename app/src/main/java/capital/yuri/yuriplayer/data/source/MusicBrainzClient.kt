package capital.yuri.yuriplayer.data.source

import android.util.Log
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
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds

/**
 * MusicBrainz + Cover Art Archive + Wikidata via shared Ktor [HttpClient].
 */
class MusicBrainzClient(
    private val http: HttpClient
) {

    data class ReleaseHit(
        val mbid: String,
        val title: String?,
        val year: Int?,
        val hasFrontCover: Boolean
    )

    data class ArtistHit(
        val mbid: String,
        val name: String,
        val imageUrl: String? = null,
        val website: String? = null,
        val links: List<ArtistLink> = emptyList()
    )

    private val rateLock = Mutex()
    private var lastRequestAt = 0L

    suspend fun searchRelease(artist: String?, album: String?): ReleaseHit? =
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
            val url = "https://musicbrainz.org/ws/2/release?query=" +
                URLEncoder.encode(query, "UTF-8") +
                "&fmt=json&limit=5"
            val body = getText(url) ?: return@withContext null
            parseReleaseSearch(body)
        }

    suspend fun searchArtist(name: String): ArtistHit? =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext null
            val trimmed = name.trim()
            val mbid = findBestArtistMbid(trimmed) ?: return@withContext null
            val detailUrl =
                "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
            val detail = getText(detailUrl) ?: return@withContext ArtistHit(mbid, trimmed)
            val hit = parseArtistDetail(detail, mbid)
            val image = hit.imageUrl
                ?: resolveWikidataImage(hit)
                ?: resolveWikipediaImage(hit)
            hit.copy(imageUrl = image)
        }

    private suspend fun findBestArtistMbid(name: String): String? {
        val queries = listOf(
            "artist:\"${escapeLucene(name)}\"",
            escapeLucene(name)
        )
        for (q in queries) {
            val url = "https://musicbrainz.org/ws/2/artist?query=" +
                URLEncoder.encode(q, "UTF-8") +
                "&fmt=json&limit=10"
            val body = getText(url) ?: continue
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
        val wdUrl = hit.links.firstOrNull {
            it.url.contains("wikidata.org", ignoreCase = true)
        }?.url ?: return null
        val qid = Regex("Q\\d+").find(wdUrl)?.value ?: return null
        val api =
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&props=claims&format=json"
        val body = getText(api) ?: return null
        return try {
            val claims = JSONObject(body)
                .optJSONObject("entities")
                ?.optJSONObject(qid)
                ?.optJSONObject("claims")
                ?: return null
            val p18 = claims.optJSONArray("P18") ?: return null
            val fileName = p18.optJSONObject(0)
                ?.optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")
                ?.optString("value")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8") +
                "?width=800"
        } catch (e: Exception) {
            Log.w(TAG, "Wikidata image failed for $qid", e)
            null
        }
    }

    private suspend fun resolveWikipediaImage(hit: ArtistHit): String? {
        val wikiUrl = hit.links.firstOrNull {
            it.url.contains("wikipedia.org", ignoreCase = true)
        }?.url ?: return null
        val path = wikiUrl.substringAfter("/wiki/").takeIf { it.isNotBlank() } ?: return null
        val lang = Regex("https?://([a-z]{2,3})\\.wikipedia").find(wikiUrl)?.groupValues?.getOrNull(1) ?: "en"
        val api = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$path"
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
                "https://coverartarchive.org/release/$mbid/front-500",
                "https://coverartarchive.org/release/$mbid/front"
            )
            for (u in urls) {
                if (downloadToFile(u, destFile)) return@withContext true
            }
            false
        }

    suspend fun downloadUrl(url: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) { downloadToFile(url, destFile) }

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
                        type == "wikidata" || resource.contains("wikidata.org") ->
                            links += ArtistLink("Wikidata", resource)
                        type.contains("wikipedia") || resource.contains("wikipedia.org") ->
                            links += ArtistLink("Wikipedia", resource)
                        type == "official homepage" || type == "website" -> {
                            if (website == null) website = resource
                            links += ArtistLink("Website", resource)
                        }
                        type.contains("discogs") -> links += ArtistLink("Discogs", resource)
                        type.contains("bandcamp") -> links += ArtistLink("Bandcamp", resource)
                        type.contains("youtube") -> links += ArtistLink("YouTube", resource)
                        type.contains("spotify") -> links += ArtistLink("Spotify", resource)
                        type.contains("soundcloud") -> links += ArtistLink("SoundCloud", resource)
                        type.contains("itunes") || type.contains("apple") ->
                            links += ArtistLink("Apple Music", resource)
                    }
                }
            }
            ArtistHit(
                mbid = mbid,
                name = name,
                imageUrl = imageUrl,
                website = website,
                links = links.distinctBy { it.url }.take(12)
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
        private const val MIN_INTERVAL_MS = 1_100L
    }
}
