package capital.yuri.yuriplayer.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.milliseconds

/**
 * Minimal MusicBrainz + Cover Art Archive + Wikidata image client.
 *
 * MB search is case-insensitive. We still rank results so short / all-caps
 * names (e.g. "TOP") prefer an exact name match over a weak first hit.
 *
 * Artist images almost never live on MB directly — we resolve Wikidata P18
 * or Wikipedia page image when url-rels include those.
 */
class MusicBrainzClient {

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

            // 1) Phrase search, then 2) loose token search — MB Lucene is case-insensitive
            val mbid = findBestArtistMbid(trimmed) ?: return@withContext null

            val detailUrl =
                "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
            val detail = getText(detailUrl) ?: return@withContext ArtistHit(mbid, trimmed)
            val hit = parseArtistDetail(detail, mbid)

            // Prefer direct image rel; else Wikidata P18; else Wikipedia summary image
            val image = hit.imageUrl
                ?: resolveWikidataImage(hit)
                ?: resolveWikipediaImage(hit)

            hit.copy(imageUrl = image)
        }

    private suspend fun findBestArtistMbid(name: String): String? {
        val queries = listOf(
            "artist:\"${escapeLucene(name)}\"",
            escapeLucene(name) // loose: helps short names like TOP
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

    /** Prefer exact name (ignore case), then highest score. */
    private fun pickBestArtistMbid(json: String, wanted: String): String? {
        return try {
            val root = JSONObject(json)
            val artists = root.optJSONArray("artists") ?: return null
            if (artists.length() == 0) return null

            val target = wanted.trim().lowercase()
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

            // Require a reasonable score for non-exact matches (avoid garbage for "TOP")
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
            // Special:FilePath redirects to a usable image URL
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
        // https://en.wikipedia.org/wiki/Artist_Name
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
            val conn = open(url)
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url → $code")
                conn.disconnect()
                return@withLock null
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            Log.w(TAG, "GET failed $url", e)
            null
        }
    }

    private suspend fun downloadToFile(url: String, dest: File): Boolean = rateLock.withLock {
        throttle()
        try {
            val conn = open(url)
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withLock false
            }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                }
            }
            conn.disconnect()
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            Log.w(TAG, "download failed $url", e)
            false
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty(
            "User-Agent",
            "YuriPlayer/1.0 (https://github.com/LizAinslie/yuriplayer)"
        )
        conn.setRequestProperty("Accept", "application/json")
        return conn
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
