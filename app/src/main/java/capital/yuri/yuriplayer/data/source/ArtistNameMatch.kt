package capital.yuri.yuriplayer.data.source

import kotlin.math.min

/**
 * Strict artist-name matching for wide-scope sources (Wikipedia, Wikidata, Discogs).
 * Token-wise: same token count, each token equal or edit-distance ≤ 1.
 * Rejects "Lil Darkie" ↔ "Lil Darlin'" (last token dist 2).
 */
object ArtistNameMatch {

    fun looksLike(wanted: String, candidate: String): Boolean {
        val a = tokens(wanted)
        val b = tokens(candidate.substringBefore("(").trim())
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Full-string containment only when token counts match after join
        // (avoid "Lil" matching everything that starts with Lil)
        if (a.size != b.size) {
            // Allow "Artist Name" vs "Artist Name Band" only if wanted is strict prefix of candidate
            if (a.size < b.size && b.take(a.size) == a) return true
            return false
        }
        return a.zip(b).all { (x, y) ->
            x == y || levenshtein(x, y) <= 1
        }
    }

    fun score(wanted: String, candidate: String): Int {
        val a = tokens(wanted)
        val b = tokens(candidate.substringBefore("(").trim())
        if (a.isEmpty() || b.isEmpty()) return -100
        if (a == b) return 100
        if (a.size != b.size) {
            return if (a.size < b.size && b.take(a.size) == a) 70 else -50
        }
        var s = 80
        a.zip(b).forEach { (x, y) ->
            if (x == y) s += 5
            else s -= levenshtein(x, y) * 10
        }
        return s
    }

    fun tokens(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotEmpty() }

    fun normalize(s: String): String =
        s.trim().lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }

    /** Collapse size variants / CDN paths of the same image asset. */
    fun imageFingerprint(url: String): String {
        val raw = url.trim().substringBefore("#").lowercase()
        // Discogs embeds the same object store key in every size URL
        val discogsKey = Regex("czm6ly9[a-z0-9_\\-]+").find(raw)?.value
        if (discogsKey != null) return "discogs:$discogsKey"

        // Wikimedia / Wikipedia: fingerprint by filename
        if (raw.contains("wikimedia.org") || raw.contains("wikipedia.org")) {
            val file = raw
                .substringAfter("special:filepath/", "")
                .ifBlank { raw.substringAfterLast('/') }
                .substringBefore("?")
            if (file.isNotBlank()) return "wiki:$file"
        }

        // Deezer / similar CDNs put size in the path
        var u = raw.substringBefore("?")
        u = u.replace(Regex("/\d+x\d+/"), "/")
        u = u.replace(Regex("/\d+/"), "/")
        return u.trimEnd('/')
    }

    /** Host + path without scheme, www, trailing slash, query. */
    fun linkFingerprint(url: String): String {
        var u = url.trim().lowercase()
        u = u.removePrefix("https://").removePrefix("http://")
        u = u.removePrefix("www.")
        u = u.substringBefore("?").substringBefore("#")
        return u.trimEnd('/')
    }

    /**
     * Prefer a bio that actually mentions the artist over a longer wrong one
     * (e.g. stuck "Lil Darlin'" Wikipedia extract for Lil Darkie).
     */
    fun preferBio(artistName: String, a: String?, b: String?): String? {
        val significant = tokens(artistName).filter { it.length > 2 }
        fun score(bio: String?): Int {
            if (bio.isNullOrBlank()) return -1
            val lower = bio.lowercase()
            var s = 0
            significant.forEach { t -> if (lower.contains(t)) s += 15 }
            // Mild length preference only as tie-breaker
            s += min(bio.length / 200, 3)
            return s
        }
        return listOfNotNull(a, b).maxByOrNull { score(it) }
    }
}
