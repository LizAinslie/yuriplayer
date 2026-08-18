package capital.yuri.yuriplayer.data.source

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/** Public Bandsintown events API (app_id identifies the client). */
class BandsintownClient(
    private val http: HttpClient,
    private val appId: String = "yuriplayer"
) {
    suspend fun upcomingEvents(artistName: String): List<ArtistEvent> = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext emptyList()
        val path = URLEncoder.encode(name, "UTF-8")
        val url =
            "https://rest.bandsintown.com/artists/$path/events?app_id=$appId&date=upcoming"
        val body = try {
            val response = http.get(url)
            if (!response.status.isSuccess()) return@withContext emptyList()
            response.bodyAsText()
        } catch (e: Exception) {
            Log.w(TAG, "events failed for $name", e)
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
                    val id = o.optString("id").ifBlank { "$name-$i" }
                    add(
                        ArtistEvent(
                            id = id,
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
            Log.w(TAG, "parse events failed", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "Bandsintown"
    }
}
