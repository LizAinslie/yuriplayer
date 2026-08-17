package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.db.ArtistProfileDao
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.source.ArtistProfile
import capital.yuri.yuriplayer.data.source.ArtistProfileProvider
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
    private val images: UserImageStore
) {

    fun observe(artistName: String): Flow<ArtistProfile?> {
        val key = artistKey(artistName) ?: return kotlinx.coroutines.flow.flowOf(null)
        return dao.observe(key).map { entity ->
            entity?.toProfile(parseLinks(entity.linksJson))
        }
    }

    suspend fun resolve(artistName: String): ArtistProfile? = withContext(Dispatchers.IO) {
        val key = artistKey(artistName) ?: return@withContext null
        val cached = dao.get(key)?.toProfile(parseLinks(dao.get(key)?.linksJson))

        var merged = cached ?: ArtistProfile(artistKey = key, displayName = artistName.trim())
        for (provider in providers) {
            val fetched = runCatching { provider.fetch(artistName) }.getOrNull() ?: continue
            merged = merge(merged, fetched)
        }

        dao.upsert(
            ArtistProfileEntity(
                artistKey = merged.artistKey,
                displayName = merged.displayName,
                bio = merged.bio,
                imageUri = merged.imageUri,
                websiteUrl = merged.websiteUrl,
                linksJson = linksToJson(merged.links),
                source = merged.source,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        merged
    }

    /**
     * User-picked profile image. Copies into app storage so it persists.
     * Pass null to clear (falls back to provider art on next resolve).
     */
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
                source = if (persisted != null) "user" else (existing?.source ?: "local"),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Wide banner for artist page theming. Independent of circular profile image.
     */
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

    /** Current banner file:// URI if set. */
    fun bannerUri(artistName: String): String? {
        val key = artistKey(artistName) ?: return null
        return images.resolve(UserImageStore.NS_ARTIST_BANNERS, key)
    }

    private fun merge(base: ArtistProfile, incoming: ArtistProfile): ArtistProfile =
        base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = incoming.bio ?: base.bio,
            imageUri = if (base.source == "user" && base.imageUri != null) base.imageUri
            else incoming.imageUri ?: base.imageUri,
            websiteUrl = incoming.websiteUrl ?: base.websiteUrl,
            links = if (incoming.links.isNotEmpty()) incoming.links else base.links,
            source = when {
                base.source == "user" -> "user"
                incoming.imageUri != null || incoming.bio != null -> incoming.source
                else -> base.source
            }
        )

    companion object {
        fun parseLinks(json: String?): List<ArtistLink> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(ArtistLink(o.optString("label"), o.optString("url")))
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun linksToJson(links: List<ArtistLink>): String? {
            if (links.isEmpty()) return null
            val arr = JSONArray()
            links.forEach { link ->
                arr.put(JSONObject().put("label", link.label).put("url", link.url))
            }
            return arr.toString()
        }
    }
}
