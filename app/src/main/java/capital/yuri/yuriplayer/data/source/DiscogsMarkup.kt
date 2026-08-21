package capital.yuri.yuriplayer.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Discogs profile wiki: `[l1661982]`, `[a3460770]`, `[a=Name]`, `[url=…]`.
 * Resolve ids to names so bios are readable.
 */
object DiscogsMarkup {
    private val NAMED = Regex("""\[(a|l|r|m)=([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val PIPED = Regex("""\[(a|l|r|m)(\d+)\|([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""\[(a|l|r|m)(\d+)\]""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""\[url=([^\]]+)\](.*?)\[/url\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE = Regex("""\[/?[biu]\]""", RegexOption.IGNORE_CASE)

    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(raw: String?, discogs: DiscogsClient): String? {
        if (raw.isNullOrBlank()) return raw
        var t: String = raw
        t = URL.replace(t) { it.groupValues[2].ifBlank { it.groupValues[1] } }
        t = STYLE.replace(t, "")
        t = NAMED.replace(t) { it.groupValues[2] }
        t = PIPED.replace(t) { it.groupValues[3] }
        val ids = BARE.findAll(t).map { it.groupValues[1].lowercase() to it.groupValues[2] }.distinct().toList()
        for ((kind, id) in ids) {
            val name = resolveEntity(discogs, kind, id) ?: continue
            t = t.replace("[$kind$id]", name, ignoreCase = true)
        }
        return t.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private suspend fun resolveEntity(discogs: DiscogsClient, kind: String, id: String): String? {
        val cacheKey = "$kind:$id"
        cache[cacheKey]?.let { return it }
        val resource = when (kind.lowercase()) {
            "a" -> "artists"
            "l" -> "labels"
            "r" -> "releases"
            "m" -> "masters"
            else -> return null
        }
        return withContext(Dispatchers.IO) {
            val json = discogs.entity(resource, id) ?: return@withContext null
            val name = json.optString("name").takeIf { it.isNotBlank() }
                ?: json.optString("title").takeIf { it.isNotBlank() }
                ?: return@withContext null
            cache[cacheKey] = name
            name
        }
    }
}
