package capital.yuri.yuriplayer.data.source

import android.util.Log
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
 * TheAudioDB free API (demo key "2").
 * Images, bio, genres, social / website links.
 */
class AudioDbArtistImageSource(
    private val http: HttpClient
) : ArtistInfoSource {

    override val id: String = "theaudiodb"
    override val displayName: String = "TheAudioDB"

    override suspend fun fetchProfile(artistName: String): ArtistProfile? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            val a = firstArtist(artistName) ?: return@withContext null
            val name = a.optString("strArtist").ifBlank { artistName.trim() }
            val bio = a.optString("strBiographyEN").takeIf { it.isNotBlank() && it != "null" }
            val genres = buildList {
                a.optString("strGenre").takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
                a.optString("strStyle").takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
            }.flatMap { it.split(',', '/', '|') }.map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }

            val links = buildList {
                fun addUrl(raw: String?, label: String) {
                    val u = raw?.takeIf { it.isNotBlank() && it != "null" } ?: return
                    val url = if (u.startsWith("http")) u else "https://$u"
                    add(categorizeLink(url, label))
                }
                addUrl(a.optString("strWebsite"), "Website")
                addUrl(a.optString("strFacebook"), "Facebook")
                addUrl(a.optString("strTwitter"), "X / Twitter")
                addUrl(a.optString("strLastFMChart"), "Last.fm")
            }.distinctBy { it.url.lowercase() }

            ArtistProfile(
                artistKey = key,
                displayName = name,
                bio = bio,
                websiteUrl = links.firstOrNull { it.category == LinkCategory.OFFICIAL }?.url,
                links = links,
                genres = genres,
                source = id
            )
        }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val a = firstArtist(artistName) ?: return@withContext emptyList()
        val name = a.optString("strArtist").ifBlank { artistName }
        val out = LinkedHashMap<String, ArtistImageCandidate>()
        val fields = when (kind) {
            ArtistImageKind.PROFILE -> listOf(
                "strArtistThumb" to "AudioDB thumb",
                "strArtistWideThumb" to "AudioDB wide",
                "strArtistClearart" to "AudioDB clearart",
                "strArtistCutout" to "AudioDB cutout"
            )
            ArtistImageKind.BANNER -> listOf(
                "strArtistFanart" to "AudioDB fanart",
                "strArtistFanart2" to "AudioDB fanart 2",
                "strArtistFanart3" to "AudioDB fanart 3",
                "strArtistBanner" to "AudioDB banner"
            )
        }
        fields.forEach { (field, label) ->
            val url = a.optString(field).takeIf { it.isNotBlank() && it != "null" } ?: return@forEach
            if (url !in out) out[url] = ArtistImageCandidate(url, id, "$label · $name")
        }
        out.values.toList()
    }

    private suspend fun firstArtist(artistName: String): JSONObject? {
        val requestUrl = url("https://www.theaudiodb.com") {
            path("api", "v1", "json", API_KEY, "search.php")
            param("s", artistName.trim())
        }
        val body = get(requestUrl)
            ?: return null
        return try {
            JSONObject(body).optJSONArray("artists")?.optJSONObject(0)
        } catch (e: Exception) {
            Log.w(TAG, "parse failed", e)
            null
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
        private const val TAG = "AudioDbArtist"
        private const val API_KEY = "2"
    }
}
