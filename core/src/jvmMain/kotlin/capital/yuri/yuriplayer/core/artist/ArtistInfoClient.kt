package capital.yuri.yuriplayer.core.artist

import capital.yuri.yuriplayer.core.http.url
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia + TheAudioDB — same sources mobile uses for bios and banners.
 */
class ArtistInfoClient(
    private val http: HttpClient,
    private val store: ArtistProfileStore
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun resolve(name: String, force: Boolean = false): ArtistProfile = withContext(Dispatchers.IO) {
        val cached = store.load(name)
        val fresh = cached != null &&
            !force &&
            (System.currentTimeMillis() - cached.updatedAtMs) < CACHE_TTL_MS &&
            (!cached.bio.isNullOrBlank() || !cached.bannerUri.isNullOrBlank() || cached.bannerCleared)
        if (fresh && cached != null) return@withContext cached

        val remote = fetchRemote(name)
        val merged = merge(name, cached, remote)
        store.save(merged)
        merged
    }

    suspend fun bannerCandidates(name: String): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        coroutineScope {
            val wiki = async { wikipediaImages(name) }
            val adb = async { audioDbImages(name) }
            (adb.await() + wiki.await()).distinctBy { it.url }
        }
    }

    suspend fun applyBannerUrl(name: String, url: String): ArtistProfile {
        val bytes = getBytes(url) ?: error("Couldn't download image")
        val uri = store.writeBannerBytes(name, bytes) ?: error("Couldn't save banner")
        val current = store.load(name) ?: ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = name
        )
        val next = current.copy(
            bannerUri = uri,
            bannerCleared = false,
            source = listOf(current.source, "user").filter { it.isNotBlank() }.distinct().joinToString(","),
            updatedAtMs = System.currentTimeMillis()
        )
        store.save(next)
        return next
    }

    fun applyLocalBanner(name: String, file: java.io.File): ArtistProfile {
        val uri = store.setLocalBanner(name, file) ?: error("Couldn't save banner")
        val current = store.load(name) ?: ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = name
        )
        val next = current.copy(
            bannerUri = uri,
            bannerCleared = false,
            source = "user",
            updatedAtMs = System.currentTimeMillis()
        )
        store.save(next)
        return next
    }

    fun clearBanner(name: String): ArtistProfile {
        store.clearBanner(name)
        val current = store.load(name) ?: ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = name
        )
        val next = current.copy(
            bannerUri = null,
            bannerCleared = true,
            updatedAtMs = System.currentTimeMillis()
        )
        store.save(next)
        return next
    }

    private suspend fun fetchRemote(name: String): ArtistProfile {
        return coroutineScope {
            val wiki = async { wikipediaProfile(name) }
            val adb = async { audioDbProfile(name) }
            merge(name, wiki.await(), adb.await())
        }
    }

    private fun merge(name: String, a: ArtistProfile?, b: ArtistProfile?): ArtistProfile {
        val key = artistKey(name) ?: name.lowercase()
        if (a == null && b == null) {
            return ArtistProfile(artistKey = key, displayName = name.trim(), updatedAtMs = System.currentTimeMillis())
        }
        if (a == null) return b!!.copy(updatedAtMs = System.currentTimeMillis())
        if (b == null) return a.copy(updatedAtMs = System.currentTimeMillis())
        return a.copy(
            displayName = a.displayName.ifBlank { b.displayName },
            bio = preferBio(a.bio, b.bio),
            imageUri = a.imageUri ?: b.imageUri,
            bannerUri = when {
                a.bannerCleared -> null
                !a.bannerUri.isNullOrBlank() -> a.bannerUri
                else -> b.bannerUri
            },
            source = listOf(a.source, b.source).filter { it.isNotBlank() }.distinct().joinToString(","),
            bannerCleared = a.bannerCleared,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun preferBio(a: String?, b: String?): String? {
        val aa = a?.trim()?.takeIf { it.isNotBlank() }
        val bb = b?.trim()?.takeIf { it.isNotBlank() }
        if (aa == null) return bb
        if (bb == null) return aa
        return if (aa.length >= bb.length) aa else bb
    }

    private suspend fun wikipediaProfile(name: String): ArtistProfile? {
        val summary = wikipediaSummary(name) ?: return null
        val title = summary.str("title") ?: name
        val extract = summary.str("extract")?.takeIf { it.isNotBlank() } ?: return null
        if (isWork(summary.str("description"), extract)) return null
        val image = summary.obj("originalimage")?.str("source")
            ?: summary.obj("thumbnail")?.str("source")
        val banner = summary.obj("originalimage")?.let { img ->
            val w = img.int("width") ?: 0
            val h = img.int("height") ?: 0
            if (w >= h && w >= 800) img.str("source") else null
        }
        return ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = title,
            bio = extract,
            imageUri = image,
            bannerUri = banner,
            source = "wikipedia"
        )
    }

    private suspend fun wikipediaImages(name: String): List<ArtistImageCandidate> {
        val summary = wikipediaSummary(name) ?: return emptyList()
        val title = summary.str("title") ?: name
        val out = ArrayList<ArtistImageCandidate>()
        summary.obj("originalimage")?.str("source")?.let {
            out += ArtistImageCandidate(it, "wikipedia", "Wikipedia · $title", summary.obj("originalimage")?.int("width"), summary.obj("originalimage")?.int("height"))
        }
        summary.obj("thumbnail")?.str("source")?.let {
            out += ArtistImageCandidate(it, "wikipedia", "Wikipedia thumb · $title")
        }
        return out
    }

    private suspend fun wikipediaSummary(name: String): JsonObject? {
        val titles = listOf(
            name.trim(),
            "${name.trim()} (band)",
            "${name.trim()} (musician)",
            "${name.trim()} (duo)"
        )
        for (title in titles) {
            val requestUrl = url("https://en.wikipedia.org") {
                path("api", "rest_v1", "page", "summary", title.replace(' ', '_'), encodeSlash = true)
            }
            val body = getText(requestUrl) ?: continue
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            if (root.str("type") == "disambiguation") continue
            val desc = root.str("description").orEmpty()
            val extract = root.str("extract").orEmpty()
            if (isWork(desc, extract)) continue
            if (root.str("title").isNullOrBlank()) continue
            return root
        }
        return null
    }

    private suspend fun audioDbProfile(name: String): ArtistProfile? {
        val a = audioDbArtist(name) ?: return null
        val bio = a.str("strBiographyEN")?.takeIf { it.isNotBlank() && it != "null" }
        val banner = listOf("strArtistFanart", "strArtistFanart2", "strArtistBanner")
            .firstNotNullOfOrNull { field -> a.str(field)?.takeIf { it.isNotBlank() && it != "null" } }
        val thumb = a.str("strArtistThumb")?.takeIf { it.isNotBlank() && it != "null" }
        return ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = a.str("strArtist")?.ifBlank { name } ?: name,
            bio = bio,
            imageUri = thumb,
            bannerUri = banner,
            source = "theaudiodb"
        )
    }

    private suspend fun audioDbImages(name: String): List<ArtistImageCandidate> {
        val a = audioDbArtist(name) ?: return emptyList()
        val display = a.str("strArtist") ?: name
        val fields = listOf(
            "strArtistFanart" to "AudioDB fanart",
            "strArtistFanart2" to "AudioDB fanart 2",
            "strArtistFanart3" to "AudioDB fanart 3",
            "strArtistBanner" to "AudioDB banner",
            "strArtistWideThumb" to "AudioDB wide",
            "strArtistThumb" to "AudioDB thumb"
        )
        return fields.mapNotNull { (field, label) ->
            val u = a.str(field)?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            ArtistImageCandidate(u, "theaudiodb", "$label · $display")
        }
    }

    private suspend fun audioDbArtist(name: String): JsonObject? {
        val requestUrl = url("https://www.theaudiodb.com") {
            path("api", "v1", "json", "2", "search.php")
            param("s", name.trim())
        }
        val body = getText(requestUrl) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["artists"]?.jsonArray?.firstOrNull()?.jsonObject
    }

    private fun isWork(description: String?, extract: String): Boolean {
        val d = (description.orEmpty() + " " + extract.take(280)).lowercase()
        val work = listOf(" album", " song by", " single by", " ep by", " soundtrack", " mixtape")
        val artist = listOf("musician", "singer", "rapper", "band", "duo", "group", "artist", "dj")
        if (work.any { d.contains(it) } && artist.none { d.contains(it) }) return true
        return false
    }

    private suspend fun getText(url: String): String? = runCatching {
        val response = http.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    }.getOrNull()

    private suspend fun getBytes(url: String): ByteArray? = runCatching {
        val response = http.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status.isSuccess()) response.bodyAsBytes() else null
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.jsonObject

    companion object {
        private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
        private const val USER_AGENT =
            "YuriPlayer/1.0 (desktop; https://github.com/LizAinslie/yuriplayer)"
    }
}
