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
    private val providers: List<ArtistProfileProvider>
) {

    fun observe(artistName: String): Flow<ArtistProfile?> {
        val key = artistKey(artistName) ?: return kotlinx.coroutines.flow.flowOf(null)
        return dao.observe(key).map { entity ->
            entity?.toProfile(parseLinks(entity.linksJson))
        }
    }

    /**
     * Fetch from providers (local first, then remotes when registered),
     * merge non-null fields, persist, return.
     */
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

    private fun merge(base: ArtistProfile, incoming: ArtistProfile): ArtistProfile =
        base.copy(
            displayName = incoming.displayName.ifBlank { base.displayName },
            bio = incoming.bio ?: base.bio,
            imageUri = incoming.imageUri ?: base.imageUri,
            websiteUrl = incoming.websiteUrl ?: base.websiteUrl,
            links = if (incoming.links.isNotEmpty()) incoming.links else base.links,
            source = if (incoming.imageUri != null || incoming.bio != null) incoming.source else base.source
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
