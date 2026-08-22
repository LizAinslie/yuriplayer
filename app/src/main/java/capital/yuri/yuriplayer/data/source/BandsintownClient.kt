package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Bandsintown events API.
 *
 * As of 2025 the public "pick any app_id" model is dead — every unapproved
 * app_id gets AWS IAM 403 "explicit deny". Access is:
 *  - Artist/manager keys (single-artist only) via Bandsintown for Artists, or
 *  - Partner keys negotiated at API@bandsintown.com
 *
 * Pass a real key via [appId] when you have one; otherwise we soft-fail empty.
 */
class BandsintownClient(
    private val http: HttpClient,
    /** Partner / approved app_id. Null or blank → skip network (no point 403-ing). */
    private val appId: String? = null
) {
    suspend fun upcomingEvents(artistName: String): List<ArtistEvent> = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext emptyList()
        val id = appId?.trim().orEmpty()
        if (id.isEmpty()) {
            log.i { "skip events for $name — no partner app_id configured" }
            return@withContext emptyList()
        }
        val requestUrl = url("https://rest.bandsintown.com") {
            path("artists", name, "events", encodeSlash = true)
            param("app_id", id)
            param("date", "upcoming")
        }
        val body = try {
            val response = http.get(requestUrl)
            if (response.status.value == 403) {
                log.w { "403 for $name — app_id is not partner-approved. " +
                        "Request access at API@bandsintown.com or use a Bandsintown-for-Artists key." }
                return@withContext emptyList()
            }
            if (!response.status.isSuccess()) {
                log.w { "events ${response.status} for $name" }
                return@withContext emptyList()
            }
            response.bodyAsText()
        } catch (e: Exception) {
            log.w(e) { "events failed for $name" }
            return@withContext emptyList()
        }
        // API returns [] or error object
        if (body.isBlank() || body.trimStart().startsWith("{")) return@withContext emptyList()
        try {
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until minOf(arr.length(), 12)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val venue = o.optJSONObject("venue")
                    val eventId = o.optString("id").ifBlank { "$name-$i" }
                    add(
                        ArtistEvent(
                            id = eventId,
                            title = o.optString("title").ifBlank { name },
                            venue = venue?.optString("name")?.takeIf { it.isNotBlank() },
                            city = venue?.optString("city")?.takeIf { it.isNotBlank() },
                            region = venue?.optString("region")?.takeIf { it.isNotBlank() },
                            country = venue?.optString("country")?.takeIf { it.isNotBlank() },
                            datetime = o.optString("datetime").takeIf { it.isNotBlank() }
                                ?: o.optString("starts_at").takeIf { it.isNotBlank() },
                            url = o.optString("url").takeIf { it.isNotBlank() },
                            source = "bandsintown"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.w(e) { "parse events failed" }
            emptyList()
        }
    }

    companion object {
        private val log = yuriLog("Bandsintown")
        /** Deep-link fallback when API access is unavailable. */
        fun publicArtistUrl(artistName: String): String {
            return url("https://www.bandsintown.com") {
                path("a", artistName.trim(), encodeSlash = true)
            }
        }
    }
}
