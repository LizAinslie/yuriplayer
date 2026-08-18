package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.ArtistProfileDao
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.source.ArtistNameMatch
import capital.yuri.yuriplayer.data.source.ArtistProfile
import capital.yuri.yuriplayer.data.source.ArtistProfileProvider
import capital.yuri.yuriplayer.data.source.LinkCategory
import capital.yuri.yuriplayer.data.source.categorizeLink
import capital.yuri.yuriplayer.data.source.genresToJson
import capital.yuri.yuriplayer.data.source.parseGenresJson
import capital.yuri.yuriplayer.data.source.toProfile
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
    private val artistInfo: ArtistInfoService? = null
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
        val entity = dao.get(key)
        var merged = entity?.toProfile(
            links = parseLinks(entity.linksJson),
            genres = parseGenresJson(entity.genresJson)
        ) ?: ArtistProfile(artistKey = key, displayName = artistName.trim())

        for (provider in providers) {
            val fetched = runCatching { provider.fetch(artistName) }.getOrNull() ?: continue
            merged = merge(artistName, merged, fetched)
        }
        artistInfo?.let { info ->
            runCatching { info.resolveProfile(artistName) }.getOrNull()?.let {
                merged = merge(artistName, merged, it)
            }
        }

        merged = ensureDiscoveryLinks(merged)
        merged = preferLocalImage(merged, key)

        // Drop links that are pure search fallbacks when a real platform link exists
        merged = merged.copy(
            links = dedupeLinksPreferCanonical(merged.links),
            bio = cleanedBio(artistName, merged.bio)
        )

        dao.upsert(
            ArtistProfileEntity(
                artistKey = merged.artistKey,
                displayName = merged.displayName,
                bio = merged.bio,
                imageUri = merged.imageUri,
                websiteUrl = merged.websiteUrl,
                linksJson = linksToJson(merged.links),
                genresJson = genresToJson(merged.genres),
                source = merged.source,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        merged
    }

    suspend fun setCustomImage(artistName: String, imageUri: String?) = withContext(Dispatchers.IO) {
        val key = artistKey(artistName) ?: return@withContext
        val existing = dao.get(key)
        val persisted = if (imageUri.isNullOrBlank()) {
            images.delete(UserImageStore.NS_ARTISTS, key)
            null
        } else {
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
                source = if (persisted != null) "user" else (existing?.source ?: "local"),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun setBannerImage(artistName: String, imageUri: String?): String? =
        withContext(Dispatchers.IO) {
            val key = artistKey(artistName) ?: return@withContext null
            if (imageUri.isNullOrBlank()) {
                images.delete(UserImageStore.NS_ARTIST_BANNERS, key)
                null
            } else {
                images.persist(imageUri, UserImageStore.NS_ARTIST_BANNERS, key)
            }
        }

    fun bannerUri(artistName: String): String? {
        val key = artistKey(artistName) ?: return null
        return images.resolve(UserImageStore.NS_ARTIST_BANNERS, key)
    }

    private fun preferLocalImage(profile: ArtistProfile, key: String): ArtistProfile {
        val local = images.resolve(UserImageStore.NS_ARTISTS, key) ?: return profile
        if (profile.imageUri == local && profile.source == "user") return profile
        return profile.copy(imageUri = local, source = "user")
    }

    private fun merge(
        artistName: String,
        base: ArtistProfile,
        incoming: ArtistProfile
    ): ArtistProfile =
        base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = ArtistNameMatch.preferBio(artistName, base.bio, incoming.bio),
            imageUri = when {
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
                base.source == "user" -> "user"
                else -> listOf(base.source, incoming.source)
                    .filter { it.isNotBlank() }.distinct().joinToString(",")
            }
        )

    /**
     * If a stored bio scores as unrelated to the artist name (wrong Wikipedia hit),
     * drop it so a better source can fill in on this or a later resolve.
     */
    private fun cleanedBio(artistName: String, bio: String?): String? {
        if (bio.isNullOrBlank()) return bio
        val significant = ArtistNameMatch.tokens(artistName).filter { it.length > 2 }
        if (significant.isEmpty()) return bio
        val lower = bio.lowercase()
        val hits = significant.count { lower.contains(it) }
        // Require at least one significant token (e.g. "darkie") for multi-word names
        return if (hits == 0 && significant.size >= 2) null else bio
    }

    private fun dedupeLinksPreferCanonical(links: List<ArtistLink>): List<ArtistLink> {
        // Prefer non-search URLs when two links share the same platform label
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
        // Second pass: one link per label for common platforms (keep non-search)
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
        val q = java.net.URLEncoder.encode(profile.displayName, "UTF-8")
        val existingFp = profile.links.map { ArtistNameMatch.linkFingerprint(it.url) }.toHashSet()
        val existingLabels = profile.links.map { it.label.lowercase() }.toHashSet()
        val extras = buildList {
            fun addIfMissing(url: String, label: String) {
                if (label.lowercase() in existingLabels) return
                if (ArtistNameMatch.linkFingerprint(url) in existingFp) return
                add(categorizeLink(url, label))
            }
            addIfMissing(
                "https://musicbrainz.org/search?query=$q&type=artist&method=indexed",
                "MusicBrainz"
            )
            addIfMissing("https://open.spotify.com/search/$q", "Spotify")
            addIfMissing("https://music.apple.com/search?term=$q", "Apple Music")
            addIfMissing("https://www.youtube.com/results?search_query=$q", "YouTube")
            addIfMissing("https://bandcamp.com/search?q=$q", "Bandcamp")
            addIfMissing("https://soundcloud.com/search?q=$q", "SoundCloud")
        }
        return profile.copy(
            links = (profile.links + extras)
                .distinctBy { ArtistNameMatch.linkFingerprint(it.url) }
        )
    }

    companion object {
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
