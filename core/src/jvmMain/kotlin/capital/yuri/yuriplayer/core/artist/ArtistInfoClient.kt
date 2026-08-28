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
 * Same public sources as mobile: Wikipedia, TheAudioDB, Deezer, MusicBrainz, Wikidata,
 * plus library servers (Jellyfin / Navidrome) supplied by the host.
 */
class ArtistInfoClient(
    private val http: HttpClient,
    private val store: ArtistProfileStore,
    private val libraryImages: suspend (String) -> List<ArtistImageCandidate> = { emptyList() }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun resolve(name: String, force: Boolean = false): ArtistProfile = withContext(Dispatchers.IO) {
        val cached = store.load(name)
        val bannerLooksWrong = cached?.bannerUri.let { uri ->
            uri.isNullOrBlank() ||
                uri.contains("wikipedia", true) ||
                uri.contains("wikimedia", true)
        }
        val fresh = cached != null &&
            !force &&
            !bannerLooksWrong &&
            (System.currentTimeMillis() - cached.updatedAtMs) < CACHE_TTL_MS &&
            (!cached.bio.isNullOrBlank() || !cached.bannerUri.isNullOrBlank() || cached.bannerCleared)
        if (fresh && cached != null) return@withContext cached

        val remote = fetchRemote(name)
        var merged = merge(name, cached, remote)
        if (!merged.bannerCleared) {
            val wide = pickWideBanner(name, merged.bannerUri)
            if (wide != null) merged = merged.copy(bannerUri = wide)
        }
        store.save(merged)
        merged
    }

    suspend fun bannerCandidates(name: String): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        coroutineScope {
            val wiki = async { wikipediaImages(name) }
            val adb = async { audioDbImages(name) }
            val deezer = async { deezerImages(name) }
            val mb = async { musicBrainzImages(name) }
            val wd = async { wikidataImages(name) }
            val lib = async { runCatching { libraryImages(name) }.getOrDefault(emptyList()) }
            (adb.await() + lib.await() + wiki.await() + deezer.await() + mb.await() + wd.await())
                .distinctBy { it.url }
                .sortedByDescending { it.headerScore() }
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
            val parts = listOf(
                async { wikipediaProfile(name) },
                async { audioDbProfile(name) },
                async { musicBrainzProfile(name) },
                async { wikidataProfile(name) }
            ).map { runCatching { it.await() }.getOrNull() }
            parts.fold(null as ArtistProfile?) { acc, p -> merge(name, acc, p) }
                ?: ArtistProfile(
                    artistKey = artistKey(name) ?: name.lowercase(),
                    displayName = name.trim(),
                    updatedAtMs = System.currentTimeMillis()
                )
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
                a.bannerUri.isWideHeaderUrl() -> a.bannerUri
                b.bannerUri.isWideHeaderUrl() -> b.bannerUri
                !a.bannerUri.isNullOrBlank() && !a.bannerUri.isPortraitSource() -> a.bannerUri
                else -> b.bannerUri?.takeUnless { it.isPortraitSource() }
            },
            genres = (a.genres + b.genres).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
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
            val h = img.int("height") ?: 1
            if (h > 0 && w.toFloat() / h >= 2.4f) img.str("source") else null
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
        val banner = listOf("strArtistBanner", "strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistWideThumb")
            .firstNotNullOfOrNull { field -> a.str(field)?.takeIf { it.isNotBlank() && it != "null" } }
        val thumb = a.str("strArtistThumb")?.takeIf { it.isNotBlank() && it != "null" }
        val genres = buildList {
            a.str("strGenre")?.takeIf { it != "null" }?.let { add(it) }
            a.str("strStyle")?.takeIf { it != "null" }?.let { add(it) }
        }.flatMap { it.split(',', '/', '|') }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        return ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = a.str("strArtist")?.ifBlank { name } ?: name,
            bio = bio,
            imageUri = thumb,
            bannerUri = banner,
            genres = genres,
            source = "theaudiodb"
        )
    }

    private suspend fun audioDbImages(name: String): List<ArtistImageCandidate> {
        val a = audioDbArtist(name) ?: return emptyList()
        val display = a.str("strArtist") ?: name
        val fields = listOf(
            "strArtistBanner" to "AudioDB banner",
            "strArtistFanart" to "AudioDB fanart",
            "strArtistFanart2" to "AudioDB fanart 2",
            "strArtistFanart3" to "AudioDB fanart 3",
            "strArtistWideThumb" to "AudioDB wide"
        )
        return fields.mapNotNull { (field, label) ->
            val u = a.str(field)?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val banner = label.contains("banner", true)
            ArtistImageCandidate(
                u, "theaudiodb", "$label · $display",
                width = if (banner) 1500 else 1280,
                height = if (banner) 500 else 720
            )
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

    private suspend fun deezerImages(name: String): List<ArtistImageCandidate> {
        val requestUrl = url("https://api.deezer.com") {
            path("search", "artist")
            param("q", name.trim())
            param("limit", 8)
        }
        val body = getText(requestUrl) ?: return emptyList()
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<ArtistImageCandidate>()
        for (el in data) {
            val a = el.jsonObject
            val artistName = a.str("name") ?: continue
            val pic = listOf("picture_xl", "picture_big", "picture_medium")
                .firstNotNullOfOrNull { a.str(it) }
                ?.takeIf { !it.contains("artist-default") } ?: continue
            out += ArtistImageCandidate(pic, "deezer", "Deezer · $artistName", 1000, 1000)
        }
        return out
    }

    private suspend fun musicBrainzProfile(name: String): ArtistProfile? {
        val searchUrl = url("https://musicbrainz.org") {
            path("ws", "2", "artist")
            param("query", "artist:\"${name.trim()}\"")
            param("fmt", "json")
            param("limit", 3)
        }
        val body = getText(searchUrl) ?: return null
        val artists = runCatching { json.parseToJsonElement(body).jsonObject["artists"]?.jsonArray }.getOrNull()
            ?: return null
        val first = artists.firstOrNull()?.jsonObject ?: return null
        val mbid = first.str("id") ?: return null
        val display = first.str("name") ?: name
        val genres = first["tags"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject.str("name")
        }.take(8)
        val detailUrl = url("https://musicbrainz.org") {
            path("ws", "2", "artist", mbid)
            param("inc", "url-rels+tags+genres")
            param("fmt", "json")
        }
        val detailBody = getText(detailUrl)
        val detail = detailBody?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val moreGenres = detail?.get("genres")?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") }
        val relations = detail?.get("relations")?.jsonArray
        var wikiImage: String? = null
        relations?.forEach { rel ->
            val obj = rel.jsonObject
            val type = obj.str("type").orEmpty()
            val urlStr = obj.obj("url")?.str("resource") ?: return@forEach
            if (type.contains("wikidata", true)) {
                val qid = Regex("Q\\d+").find(urlStr)?.value
                if (qid != null) wikiImage = wikidataP18(qid).firstOrNull()
            }
        }
        return ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = display,
            imageUri = wikiImage,
            genres = (genres + moreGenres).distinctBy { it.lowercase() },
            source = "musicbrainz"
        )
    }

    private suspend fun musicBrainzImages(name: String): List<ArtistImageCandidate> {
        val p = musicBrainzProfile(name) ?: return emptyList()
        val url = p.imageUri ?: return emptyList()
        return listOf(ArtistImageCandidate(url, "musicbrainz", "MusicBrainz · ${p.displayName}"))
    }

    private suspend fun wikidataProfile(name: String): ArtistProfile? {
        val qid = wikidataSearch(name) ?: return null
        val genres = wikidataGenres(qid)
        val images = wikidataP18(qid)
        return ArtistProfile(
            artistKey = artistKey(name) ?: name.lowercase(),
            displayName = name.trim(),
            imageUri = images.firstOrNull(),
            genres = genres,
            source = "wikidata"
        )
    }

    private suspend fun wikidataImages(name: String): List<ArtistImageCandidate> {
        val qid = wikidataSearch(name) ?: return emptyList()
        return wikidataP18(qid).mapIndexed { i, u ->
            ArtistImageCandidate(u, "wikidata", "Wikidata P18${if (i > 0) " #${i + 1}" else ""} · $name")
        }
    }

    private suspend fun wikidataSearch(name: String): String? {
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbsearchentities")
            param("search", name.trim())
            param("language", "en")
            param("type", "item")
            param("limit", 8)
            param("format", "json")
        }
        val body = getText(requestUrl) ?: return null
        val search = runCatching { json.parseToJsonElement(body).jsonObject["search"]?.jsonArray }.getOrNull()
            ?: return null
        val hints = listOf("musician", "singer", "rapper", "band", "duo", "group", "artist", "dj")
        val works = listOf("album", "song", "single", "ep", "soundtrack")
        for (el in search) {
            val o = el.jsonObject
            val id = o.str("id") ?: continue
            if (!id.startsWith("Q")) continue
            val desc = o.str("description").orEmpty().lowercase()
            if (works.any { desc.contains(it) } && hints.none { desc.contains(it) }) continue
            return id
        }
        return search.firstOrNull()?.jsonObject?.str("id")
    }

    private suspend fun wikidataP18(qid: String): List<String> {
        val claims = wikidataClaims(qid) ?: return emptyList()
        val p18 = claims["P18"]?.jsonArray ?: return emptyList()
        return p18.mapNotNull { claim ->
            val file = claim.jsonObject.obj("mainsnak")?.obj("datavalue")?.str("value") ?: return@mapNotNull null
            url("https://commons.wikimedia.org") {
                path("wiki", "Special:FilePath", file.replace(' ', '_'), encodeSlash = true)
                param("width", 1200)
            }
        }
    }

    private suspend fun wikidataGenres(qid: String): List<String> {
        val claims = wikidataClaims(qid) ?: return emptyList()
        val p136 = claims["P136"]?.jsonArray ?: return emptyList()
        val ids = p136.mapNotNull { claim ->
            claim.jsonObject.obj("mainsnak")?.obj("datavalue")?.obj("value")?.str("id")
        }.take(10)
        if (ids.isEmpty()) return emptyList()
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbgetentities")
            param("ids", ids.joinToString("|"))
            param("props", "labels")
            param("languages", "en")
            param("format", "json")
        }
        val body = getText(requestUrl) ?: return emptyList()
        val entities = runCatching { json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject }.getOrNull()
            ?: return emptyList()
        return ids.mapNotNull { id ->
            entities[id]?.jsonObject?.obj("labels")?.obj("en")?.str("value")
        }
    }

    private suspend fun wikidataClaims(qid: String): JsonObject? {
        val requestUrl = url("https://www.wikidata.org") {
            path("w", "api.php")
            param("action", "wbgetentities")
            param("ids", qid)
            param("props", "claims")
            param("format", "json")
        }
        val body = getText(requestUrl) ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
                ?.get(qid)?.jsonObject?.obj("claims")
        }.getOrNull()
    }

    private suspend fun pickWideBanner(name: String, current: String?): String? {
        if (current.isWideHeaderUrl() && !current.isPortraitSource()) return current
        val cands = runCatching { bannerCandidates(name) }.getOrDefault(emptyList())
        return cands.firstOrNull { it.headerScore() >= 50 }?.url
            ?: current?.takeUnless { it.isPortraitSource() }
    }

    private fun ArtistImageCandidate.headerScore(): Int {
        val w = width ?: 0
        val h = height ?: 0
        val ratio = if (h > 0) w.toFloat() / h else 0f
        val label = label.lowercase()
        var score = 0
        if (ratio >= 2.4f) score += 80
        else if (ratio >= 1.7f) score += 50
        else if (ratio in 0.01f..0.99f) score -= 40
        if (label.contains("banner")) score += 40
        if (label.contains("fanart") || label.contains("backdrop") || label.contains("wide")) score += 25
        if (sourceId == "theaudiodb" || sourceId == "jellyfin") score += 10
        if (sourceId == "wikipedia" || sourceId == "wikidata" || sourceId == "deezer") score -= 20
        return score
    }

    private fun String?.isWideHeaderUrl(): Boolean {
        val u = this ?: return false
        if (u.startsWith("file:")) return true
        return u.contains("banner", true) || u.contains("fanart", true) || u.contains("Backdrop", true)
    }

    private fun String?.isPortraitSource(): Boolean {
        val u = this ?: return false
        return u.contains("wikipedia", true) ||
            u.contains("wikimedia", true) ||
            u.contains("deezer.com", true)
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() =
        this ?: kotlinx.serialization.json.JsonArray(emptyList())

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
