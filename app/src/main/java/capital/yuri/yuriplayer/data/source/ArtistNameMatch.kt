package capital.yuri.yuriplayer.data.source

import kotlin.math.min

/**
 * Strict artist-name matching for wide-scope sources (Wikipedia, Wikidata, Discogs, Deezer).
 *
 * Rules:
 * - Same token count (or candidate is wanted + suffix like "Band")
 * - Every token equal or edit-distance ≤ 1
 * - Distinctive tokens (len ≥ 4, not in WEAK) must match — stops
 *   "Lil Darkie" matching "Lil' Darlin'" (darkie≠darlin, dist 2)
 */
object ArtistNameMatch {

    private val WEAK = setOf(
        "the", "a", "an", "and", "of", "dj", "mc", "ms", "mr",
        "lil", "little", "young", "big", "da", "de", "la", "el",
        "band", "group", "duo", "trio", "orchestra", "choir"
    )

    fun looksLike(wanted: String, candidate: String): Boolean {
        val a = tokens(wanted)
        val b = tokens(candidate.substringBefore("(").trim())
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        if (a.size != b.size) {
            if (a.size < b.size && b.take(a.size) == a) {
                return distinctive(a).isEmpty() || distinctive(a).all { it in b }
            }
            return false
        }

        val pairwiseOk = a.zip(b).all { (x, y) ->
            x == y || levenshtein(x, y) <= 1
        }
        if (!pairwiseOk) return false

        val distA = distinctive(a)
        if (distA.isEmpty()) return pairwiseOk
        return distA.all { tok ->
            b.any { other ->
                other == tok || (other.length == tok.length && levenshtein(other, tok) <= 1)
            }
        }
    }

    fun score(wanted: String, candidate: String): Int {
        if (!looksLike(wanted, candidate)) return -100
        val a = tokens(wanted)
        val b = tokens(candidate.substringBefore("(").trim())
        if (a == b) return 100
        var s = 80
        a.zip(b).forEach { (x, y) ->
            if (x == y) s += 5 else s -= levenshtein(x, y) * 8
        }
        return s
    }

    fun tokens(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotEmpty() }

    fun distinctive(tokens: List<String>): List<String> =
        tokens.filter { it.length >= 4 && it !in WEAK }

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

    fun imageFingerprint(url: String): String {
        val raw = url.trim().substringBefore("#").lowercase()

        // Discogs object-store key shared across sizes
        val discogsKey = Regex("czm6ly9[a-z0-9_\\-]+").find(raw)?.value
        if (discogsKey != null) return "discogs:$discogsKey"

        // Deezer: .../images/artist/<md5>/<size>-....jpg — same md5, different size
        val deezer = Regex("/images/artist/([a-f0-9]+)/").find(raw)
        if (deezer != null) return "deezer:${deezer.groupValues[1]}"

        if (raw.contains("wikimedia.org") || raw.contains("wikipedia.org")) {
            val file = raw
                .substringAfter("special:filepath/", "")
                .ifBlank { raw.substringAfterLast('/') }
                .substringBefore("?")
                // strip size from thumb paths like /thumb/a/ab/File.jpg/800px-File.jpg
                .replace(Regex("^\\d+px-"), "")
            if (file.isNotBlank()) return "wiki:$file"
        }

        var u = raw.substringBefore("?")
        // Generic WxH in path or filename
        u = u.replace(Regex("/\\d+x\\d+[^/]*"), "/SIZE")
        u = u.replace(Regex("\\d+x\\d+"), "SIZE")
        u = u.replace(Regex("/\\d+/"), "/")
        return u.trimEnd('/')
    }

    /** Approximate pixel area from common size markers in the URL (for preferring xl). */
    fun imageSizeHint(url: String): Int {
        val m = Regex("(\\d{2,4})x(\\d{2,4})").find(url.lowercase()) ?: return 0
        val w = m.groupValues[1].toIntOrNull() ?: return 0
        val h = m.groupValues[2].toIntOrNull() ?: return 0
        return w * h
    }

    fun linkFingerprint(url: String): String {
        var u = url.trim().lowercase()
        u = u.removePrefix("https://").removePrefix("http://")
        u = u.removePrefix("www.")
        u = u.substringBefore("?").substringBefore("#")
        return u.trimEnd('/')
    }

    fun preferBio(artistName: String, a: String?, b: String?): String? {
        fun score(bio: String?): Int {
            if (bio.isNullOrBlank()) return -1
            val lower = bio.lowercase()
            val dist = distinctive(tokens(artistName))
            if (dist.isNotEmpty()) {
                val hits = dist.count { lower.contains(it) }
                if (hits == 0) return 0
                return hits * 20 + min(bio.length / 200, 3)
            }
            val full = normalize(artistName)
            return if (full.isNotEmpty() && lower.contains(full)) {
                10 + min(bio.length / 200, 3)
            } else {
                0
            }
        }
        return listOfNotNull(a, b).maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
    }

    fun bioRelevant(artistName: String, bio: String?): Boolean {
        if (bio.isNullOrBlank()) return false
        val lower = bio.lowercase()
        val dist = distinctive(tokens(artistName))
        if (dist.isNotEmpty()) return dist.any { lower.contains(it) }
        val full = normalize(artistName)
        return full.isNotEmpty() && lower.contains(full)
    }
}
