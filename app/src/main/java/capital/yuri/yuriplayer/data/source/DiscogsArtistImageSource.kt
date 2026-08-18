package capital.yuri.yuriplayer.data.source

import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds

/**
 * Discogs public API — artist search + /artists/{id} images.
 * Search thumbs are often empty; detail endpoint has the real image list.
 */
class DiscogsArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "discogs"
    override val displayName: String = "Discogs"

    private val rateLock = Mutex()
    private var lastRequestAt = 0L

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val hit = searchArtists(artistName).firstOrNull() ?: return@withContext null
            val detail = artistDetail(hit.id) ?: return@withContext ArtistProfile(
                artistKey = key,
                displayName = hit.title,
                links = listOf(
                    categorizeLink("https://www.discogs.com/artist/${hit.id}", "Discogs")
                ),
                source = id
            )
            val name = detail.optString("name").ifBlank { hit.title }
            val profile = detail.optString("profile").takeIf { it.isNotBlank() }
            val urls = detail.optJSONArray("urls")
            val links = buildList {
                add(categorizeLink("https://www.discogs.com/artist/${hit.id}", "Discogs"))
                if (urls != null) {
                    for (i in 0 until urls.length()) {
                        val u = urls.optString(i).takeIf { it.startsWith("http") } ?: continue
                        add(categorizeLink(u))
                    }
                }
            }.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }

            ArtistProfile(
                artistKey = key,
                displayName = name,
                bio = profile,
                links = links,
                source = id
            )
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val hits = searchArtists(artistName).take(2)
        if (hits.isEmpty()) return@withContext emptyList()

        val out = LinkedHashMap<String, ArtistImageCandidate>()
        for (hit in hits) {
            val detail = artistDetail(hit.id)
            val images = detail?.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val img = images.optJSONObject(i) ?: continue
                    val type = img.optString("type")
                    // Prefer full-size uri over uri150
                    val url = img.optString("uri").takeIf { it.startsWith("http") }
                        ?: img.optString("resource_url").takeIf { it.startsWith("http") }
                        ?: continue
                    val fp = ArtistNameMatch.imageFingerprint(url)
                    if (fp in out) continue
                    val w = img.optInt("width").takeIf { it > 0 }
                    val h = img.optInt("height").takeIf { it > 0 }
                    val label = buildString {
                        append("Discogs")
                        if (type.isNotBlank()) append(" · ").append(type)
                        append(" · ").append(hit.title)
                    }
                    out[fp] = ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = label,
                        width = w,
                        height = h
                    )
                }
            }

            listOfNotNull(hit.coverImage, hit.thumb).forEach { url ->
                if (!url.startsWith("http")) return@forEach
                val fp = ArtistNameMatch.imageFingerprint(url)
                if (fp !in out) {
                    out[fp] = ArtistImageCandidate(
                        url = url,
                        sourceId = id,
                        label = "Discogs · ${hit.title}"
                    )
                }
            }
        }
        out.values.toList()
    }

    private data class ArtistSearchHit(
        val id: Long,
        val title: String,
        val thumb: String?,
        val coverImage: String?
    )

    private suspend fun searchArtists(name: String): List<ArtistSearchHit> {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val url =
            "https://api.discogs.com/database/search?q=$q&type=artist&per_page=10"
        val body = get(url) ?: return emptyList()
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val wanted = name.trim()
            val hits = ArrayList<ArtistSearchHit>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                if (o.optString("type") != "artist") continue
                val id = o.optLong("id", -1L)
                if (id <= 0L) continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                if (!ArtistNameMatch.looksLike(wanted, title)) continue
                hits.add(
                    ArtistSearchHit(
                        id = id,
                        title = title,
                        thumb = o.optString("thumb").takeIf { it.startsWith("http") },
                        coverImage = o.optString("cover_image").takeIf { it.startsWith("http") }
                    )
                )
            }
            hits.sortedByDescending { ArtistNameMatch.score(wanted, it.title) }
        } catch (e: Exception) {
            Log.w(TAG, "search parse failed", e)
            emptyList()
        }
    }

    private suspend fun artistDetail(id: Long): JSONObject? {
        val body = get("https://api.discogs.com/artists/$id") ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "artist detail parse failed $id", e)
            null
        }
    }

    private suspend fun get(url: String): String? = rateLock.withLock {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) delay(wait.milliseconds)
        lastRequestAt = System.currentTimeMillis()
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

    companion object {
        private const val TAG = "DiscogsArtistImg"
        private const val MIN_INTERVAL_MS = 1_100L
    }
}
