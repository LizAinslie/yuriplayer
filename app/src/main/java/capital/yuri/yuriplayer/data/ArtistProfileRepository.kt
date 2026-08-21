package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.ArtistProfileDao
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.source.ArtistNameMatch
import capital.yuri.yuriplayer.data.source.ArtistProfile
import capital.yuri.yuriplayer.data.source.ArtistProfileProvider
import capital.yuri.yuriplayer.data.source.DiscogsMarkup
import capital.yuri.yuriplayer.data.source.LinkCategory
import capital.yuri.yuriplayer.data.source.categorizeLink
import capital.yuri.yuriplayer.data.source.genresToJson
import capital.yuri.yuriplayer.data.source.parseGenresJson
import capital.yuri.yuriplayer.data.source.toProfile
import capital.yuri.yuriplayer.http.url
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ArtistProfileRepository(
    private val dao: ArtistProfileDao,
    private val providers: List<ArtistProfileProvider>,
    private val images: UserImageStore,
    private val artistInfo: ArtistInfoService? = null,
    private val http: HttpClient? = null
) {

    fun observe(artistName: String): Flow<ArtistProfile?> {
        val key = artistKey(artistName) ?: return kotlinx.coroutines.flow.flowOf(null)
        return dao.observe(key).map { entity ->
            entity?.toProfile(
                links = parseLinks(entity.linksJson),
                genres = parseGenresJson(entity.genresJson)
            )?.let { preferLocalImage(it, key) }
        }
    }

    suspend fun resolve(artistName: String): ArtistProfile? = withContext(Dispatchers.IO) {
        val key = artistKey(artistName) ?: return@withContext null
        val imageCleared = images.isCleared(UserImageStore.NS_ARTISTS, key)

        val entity = dao.get(key)
        var merged = entity?.toProfile(
            links = parseLinks(entity.linksJson),
            genres = parseGenresJson(entity.genresJson)
        ) ?: ArtistProfile(artistKey = key, displayName = artistName.trim())

        // If the user forced a clear, strip any stored remote URI before merge
        if (imageCleared) {
            merged = merged.copy(imageUri = null, source = SOURCE_USER_CLEARED)
        }

        if (!ArtistNameMatch.bioRelevant(artistName, merged.bio)) {
            merged = merged.copy(bio = null)
        }

        for (provider in providers) {
            val fetched = runCatching { provider.fetch(artistName) }.getOrNull() ?: continue
            merged = merge(artistName, merged, fetched, imageCleared = imageCleared)
        }
        artistInfo?.let { info ->
            runCatching { info.resolveProfile(artistName) }.getOrNull()?.let {
                merged = merge(artistName, merged, it, imageCleared = imageCleared)
            }
        }

        merged = ensureDiscoveryLinks(merged)
        merged = preferLocalImage(merged, key)
        val cleanedBio = http?.let { DiscogsMarkup.resolve(merged.bio, it) } ?: merged.bio

        // Final hard veto: cleared always wins over any provider image
        if (imageCleared) {
            merged = merged.copy(imageUri = null, source = SOURCE_USER_CLEARED)
        }

        merged = merged.copy(
            links = dedupeLinksPreferCanonical(merged.links),
            bio = cleanedBio?.takeIf { ArtistNameMatch.bioRelevant(artistName, it) }
        )

        dao.upsert(
            ArtistProfileEntity(
                artistKey = merged.artistKey,
                displayName = merged.displayName,
                bio = merged.bio,
                imageUri = if (imageCleared) null else merged.imageUri,
                websiteUrl = merged.websiteUrl,
                linksJson = linksToJson(merged.links),
                genresJson = genresToJson(merged.genres),
                source = if (imageCleared) SOURCE_USER_CLEARED else merged.source,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        merged
    }

    suspend fun setCustomImage(artistName: String, imageUri: String?) = withContext(Dispatchers.IO) {
        val key = artistKey(artistName) ?: return@withContext
        val existing = dao.get(key)
        val persisted = if (imageUri.isNullOrBlank()) {
            // Forced clear — durable marker blocks auto-set until user picks art again
            images.markCleared(UserImageStore.NS_ARTISTS, key)
            null
        } else {
            // persist() clears the marker automatically
            images.persist(imageUri, UserImageStore.NS_ARTISTS, key) ?: imageUri
        }
        dao.upsert(
            ArtistProfileEntity(
                artistKey = key,
                displayName = existing?.displayName ?: artistName.trim(),
                bio = existing?.bio,
                imageUri = persisted,
                websiteUrl = existing?.websiteUrl,
                linksJson = existing?.linksJson,
                genresJson = existing?.genresJson,
                source = when {
                    persisted != null -> "user"
                    imageUri.isNullOrBlank() -> SOURCE_USER_CLEARED
                    else -> existing?.source ?: "local"
                },
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun setBannerImage(artistName: String, imageUri: String?): String? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            if (imageUri.isNullOrBlank()) {
                images.markCleared(UserImageStore.NS_ARTIST_BANNERS, key)
                null
            } else {
                // persist clears the .cleared marker
                images.persist(imageUri, UserImageStore.NS_ARTIST_BANNERS, key)
            }
        }

    fun bannerUri(artistName: String): String? {
        val key = artistKey(artistName) ?: return null
        // resolve already returns null when cleared
        return images.resolve(UserImageStore.NS_ARTIST_BANNERS, key)
    }

    /** True if the user explicitly cleared the profile image (no auto-restore). */
    fun isImageCleared(artistName: String): Boolean {
        val key = artistKey(artistName) ?: return false
        return images.isCleared(UserImageStore.NS_ARTISTS, key)
    }

    /** True if the user explicitly cleared the banner. */
    fun isBannerCleared(artistName: String): Boolean {
        val key = artistKey(artistName) ?: return false
        return images.isCleared(UserImageStore.NS_ARTIST_BANNERS, key)
    }

    private fun preferLocalImage(profile: ArtistProfile, key: String): ArtistProfile {
        if (images.isCleared(UserImageStore.NS_ARTISTS, key)) {
            return profile.copy(imageUri = null, source = SOURCE_USER_CLEARED)
        }
        val local = images.resolve(UserImageStore.NS_ARTISTS, key) ?: return profile
        if (profile.imageUri == local && profile.source == "user") return profile
        return profile.copy(imageUri = local, source = "user")
    }

    private fun merge(
        artistName: String,
        base: ArtistProfile,
        incoming: ArtistProfile,
        imageCleared: Boolean = false
    ): ArtistProfile =
        base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = ArtistNameMatch.preferBio(artistName, base.bio, incoming.bio),
            imageUri = when {
                imageCleared -> null
                base.source == SOURCE_USER_CLEARED -> null
                base.source == "user" && !base.imageUri.isNullOrBlank() -> base.imageUri
                base.imageUri?.startsWith("file:") == true -> base.imageUri
                else -> base.imageUri ?: incoming.imageUri
            },
            websiteUrl = base.websiteUrl ?: incoming.websiteUrl,
            links = (base.links + incoming.links)
                .distinctBy { ArtistNameMatch.linkFingerprint(it.url) },
            genres = (base.genres + incoming.genres)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() },
            source = when {
                imageCleared || base.source == SOURCE_USER_CLEARED -> SOURCE_USER_CLEARED
                base.source == "user" -> "user"
                else -> listOf(base.source, incoming.source)
                    .filter { it.isNotBlank() && it != SOURCE_USER_CLEARED }
                    .distinct()
                    .joinToString(",")
                    .ifBlank { "local" }
            }
        )

    private fun dedupeLinksPreferCanonical(links: List<ArtistLink>): List<ArtistLink> {
        fun isSearchish(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("/search") || u.contains("?q=") || u.contains("?query=") ||
                u.contains("?term=") || u.contains("?search_query=")
        }
        val byFp = LinkedHashMap<String, ArtistLink>()
        for (link in links) {
            val fp = ArtistNameMatch.linkFingerprint(link.url)
            val existing = byFp[fp]
            if (existing == null) {
                byFp[fp] = link
            } else if (isSearchish(existing.url) && !isSearchish(link.url)) {
                byFp[fp] = link
            }
        }
        val byLabel = LinkedHashMap<String, ArtistLink>()
        for (link in byFp.values) {
            val labelKey = link.label.lowercase()
            val existing = byLabel[labelKey]
            if (existing == null) {
                byLabel[labelKey] = link
            } else if (isSearchish(existing.url) && !isSearchish(link.url)) {
                byLabel[labelKey] = link
            }
        }
        return byLabel.values.toList()
    }

    private fun ensureDiscoveryLinks(profile: ArtistProfile): ArtistProfile {
        val q = profile.displayName
        val existingFp = profile.links.map { ArtistNameMatch.linkFingerprint(it.url) }.toHashSet()
        val existingLabels = profile.links.map { it.label.lowercase() }.toHashSet()
        val extras = buildList {
            fun addIfMissing(url: String, label: String) {
                if (label.lowercase() in existingLabels) return
                if (ArtistNameMatch.linkFingerprint(url) in existingFp) return
                add(categorizeLink(url, label))
            }
            addIfMissing(
                url("https://musicbrainz.org") {
                    path("search")
                    param("query", q)
                    param("type", "artist")
                    param("method", "indexed")
                },
                "MusicBrainz"
            )
            addIfMissing(
                url("https://open.spotify.com") { path("search", q, encodeSlash = true) },
                "Spotify"
            )
            addIfMissing(
                url("https://music.apple.com") {
                    path("search")
                    param("term", q)
                },
                "Apple Music"
            )
            addIfMissing(
                url("https://www.youtube.com") {
                    path("results")
                    param("search_query", q)
                },
                "YouTube"
            )
            addIfMissing(
                url("https://bandcamp.com") {
                    path("search")
                    param("q", q)
                },
                "Bandcamp"
            )
            addIfMissing(
                url("https://soundcloud.com") {
                    path("search")
                    param("q", q)
                },
                "SoundCloud"
            )
        }
        return profile.copy(
            links = (profile.links + extras)
                .distinctBy { ArtistNameMatch.linkFingerprint(it.url) }
        )
    }

    companion object {
        /** Profile image was explicitly removed by the user — never auto-fill. */
        const val SOURCE_USER_CLEARED = "user_cleared"

        fun parseLinks(json: String?): List<ArtistLink> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val url = o.optString("url")
                        val label = o.optString("label")
                        val cat = runCatching {
                            LinkCategory.valueOf(o.optString("category", "OTHER"))
                        }.getOrDefault(LinkCategory.OTHER)
                        if (url.isNotBlank()) {
                            add(
                                if (label.isNotBlank()) ArtistLink(label, url, cat)
                                else categorizeLink(url)
                            )
                        }
                    }
                }.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }
            }.getOrDefault(emptyList())
        }

        fun linksToJson(links: List<ArtistLink>): String? {
            if (links.isEmpty()) return null
            val arr = JSONArray()
            links.distinctBy { ArtistNameMatch.linkFingerprint(it.url) }.forEach { link ->
                arr.put(
                    JSONObject()
                        .put("label", link.label)
                        .put("url", link.url)
                        .put("category", link.category.name)
                )
            }
            return arr.toString()
        }
    }
}
