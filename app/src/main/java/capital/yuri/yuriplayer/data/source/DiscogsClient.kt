package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.http.UrlScope
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ktor Discogs wrapper (search, artist, entity lookup). Stays in :app for now;
 * split into a KMP sourceset/subproject when a second target needs it.
 *
 * Unauthenticated by default (1 req/s). Pass [token] or [key]/[secret] when
 * we have an app key for higher limits.
 */
class DiscogsClient(
    private val http: HttpClient,
    private val token: String? = null,
    private val key: String? = null,
    private val secret: String? = null
) {
    data class ArtistHit(
        val id: Long,
        val title: String,
        val thumb: String?,
        val coverImage: String?
    )

    private val rateLock = Mutex()
    private var lastRequestAt = 0L

    suspend fun searchArtists(query: String, perPage: Int = 10): List<ArtistHit> {
        val body = get("database", "search") {
            param("q", query.trim())
            param("type", "artist")
            param("per_page", perPage.coerceIn(1, 50))
        } ?: return emptyList()
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val wanted = query.trim()
            val hits = ArrayList<ArtistHit>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                if (o.optString("type") != "artist") continue
                val id = o.optLong("id", -1L)
                if (id <= 0L) continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                if (!ArtistNameMatch.looksLike(wanted, title)) continue
                hits += ArtistHit(
                    id = id,
                    title = title,
                    thumb = o.optString("thumb").takeIf { it.startsWith("http") },
                    coverImage = o.optString("cover_image").takeIf { it.startsWith("http") }
                )
            }
            hits.sortedByDescending { ArtistNameMatch.score(wanted, it.title) }
        } catch (e: Exception) {
            log.w(e) { "search parse failed" }
            emptyList()
        }
    }

    suspend fun artist(id: Long): JSONObject? = json("artists", id.toString())

    suspend fun entity(resource: String, id: String): JSONObject? = json(resource, id)

    private suspend fun json(vararg path: String): JSONObject? {
        val body = get(*path) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            log.w(e) { "json parse failed ${path.joinToString("/")}" }
            null
        }
    }

    suspend fun get(
        vararg path: String,
        query: UrlScope.() -> Unit = {}
    ): String? = rateLock.withLock {
        val requestUrl = url(API) {
            path(*path)
            query()
        }
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait.milliseconds)
        lastRequestAt = System.currentTimeMillis()
        try {
            val response = http.get(requestUrl) {
                val t = token?.trim().orEmpty()
                val k = key?.trim().orEmpty()
                val s = secret?.trim().orEmpty()
                when {
                    t.isNotEmpty() -> header("Authorization", "Discogs token=$t")
                    k.isNotEmpty() && s.isNotEmpty() ->
                        header("Authorization", "Discogs key=$k, secret=$s")
                }
            }
            if (!response.status.isSuccess()) {
                log.w { "GET $requestUrl → ${response.status}" }
                return@withLock null
            }
            response.bodyAsText()
        } catch (e: Exception) {
            log.w(e) { "GET failed $requestUrl" }
            null
        }
    }

    companion object {
        private val log = yuriLog("Discogs")
        private const val API = "https://api.discogs.com"
        private const val MIN_INTERVAL_MS = 1_100L
    }
}
