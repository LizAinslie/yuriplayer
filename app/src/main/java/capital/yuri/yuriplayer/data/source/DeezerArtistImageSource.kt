package capital.yuri.yuriplayer.data.source

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Deezer public search API — no key required. */
class DeezerArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "deezer"
    override val displayName: String = "Deezer"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = null

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        if (kind == ArtistImageKind.BANNER) return@withContext emptyList()
        val q = URLEncoder.encode(artistName.trim(), "UTF-8")
        val body = get("https://api.deezer.com/search/artist?q=$q&limit=8") ?: return@withContext emptyList()
        try {
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val out = LinkedHashMap<String, ArtistImageCandidate>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val name = a.optString("name").ifBlank { artistName }
                listOf(
                    a.optString("picture_xl"),
                    a.optString("picture_big"),
                    a.optString("picture_medium")
                ).forEach { url ->
                    if (url.isNotBlank() && !url.contains("artist-default") && url !in out) {
                        out[url] = ArtistImageCandidate(url, id, "Deezer · $name")
                    }
                }
            }
            out.values.toList()
        } catch (e: Exception) {
            Log.w(TAG, "parse failed", e)
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
        private const val TAG = "DeezerArtistImg"
    }
}
