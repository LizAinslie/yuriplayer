package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.ArtistProfileDao
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.source.ArtistInfoService
import capital.yuri.yuriplayer.data.source.ArtistLink
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

        // Legacy providers
        for (provider in providers) {
            val fetched = runCatching { provider.fetch(artistName) }.getOrNull() ?: continue
            merged = merge(merged, fetched)
        }
        // Aggregated SPI (Wikipedia bio, Wikidata genres, AudioDB, …)
        artistInfo?.let { info ->
            runCatching { info.resolveProfile(artistName) }.getOrNull()?.let { merged = merge(merged, it) }
        }

        // Always include MusicBrainz entity page + search-style streaming fallbacks when missing
        merged = ensureDiscoveryLinks(merged)

        // Prefer any already-persisted user image — never overwrite it with remote
        merged = preferLocalImage(merged, key)

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

    /** If a local file exists for this artist, force it and mark source=user. */
    private fun preferLocalImage(profile: ArtistProfile, key: String): ArtistProfile {
        val local = images.resolve(UserImageStore.NS_ARTISTS, key) ?: return profile
        if (profile.imageUri == local && profile.source == "user") return profile
        return profile.copy(imageUri = local, source = "user")
    }

    private fun merge(base: ArtistProfile, incoming: ArtistProfile): ArtistProfile =
        base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = listOfNotNull(base.bio, incoming.bio).maxByOrNull { it.length },
            imageUri = when {
                base.source == "user" && !base.imageUri.isNullOrBlank() -> base.imageUri
                base.imageUri?.startsWith("file:") == true -> base.imageUri
                else -> base.imageUri ?: incoming.imageUri
            },
            websiteUrl = base.websiteUrl ?: incoming.websiteUrl,
            links = (base.links + incoming.links).distinctBy { it.url.lowercase() },
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

    /** Deduped discovery links when platforms weren't found via MB/AudioDB. */
    private fun ensureDiscoveryLinks(profile: ArtistProfile): ArtistProfile {
        val q = java.net.URLEncoder.encode(profile.displayName, "UTF-8")
        val existing = profile.links.map { it.url.lowercase() }.toHashSet()
        val extras = buildList {
            fun addIfMissing(url: String, label: String) {
                if (url.lowercase() !in existing) add(categorizeLink(url, label))
            }
            addIfMissing(
                "https://musicbrainz.org/search?query=$q&type=artist&method=indexed",
                "MusicBrainz"
            )
            if (profile.links.none { it.label.equals("Spotify", true) }) {
                addIfMissing("https://open.spotify.com/search/$q", "Spotify")
            }
            if (profile.links.none { it.label.equals("Apple Music", true) }) {
                addIfMissing("https://music.apple.com/search?term=$q", "Apple Music")
            }
            if (profile.links.none { it.label.equals("YouTube", true) }) {
                addIfMissing("https://www.youtube.com/results?search_query=$q", "YouTube")
            }
            if (profile.links.none { it.label.equals("Bandcamp", true) }) {
                addIfMissing("https://bandcamp.com/search?q=$q", "Bandcamp")
            }
            if (profile.links.none { it.label.equals("SoundCloud", true) }) {
                addIfMissing("https://soundcloud.com/search?q=$q", "SoundCloud")
            }
        }
        return profile.copy(links = (profile.links + extras).distinctBy { it.url.lowercase() })
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
                }.distinctBy { it.url.lowercase() }
            }.getOrDefault(emptyList())
        }

        fun linksToJson(links: List<ArtistLink>): String? {
            if (links.isEmpty()) return null
            val arr = JSONArray()
            links.distinctBy { it.url.lowercase() }.forEach { link ->
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
