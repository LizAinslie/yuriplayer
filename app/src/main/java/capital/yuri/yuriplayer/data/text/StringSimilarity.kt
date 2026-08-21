package capital.yuri.yuriplayer.data.text

import android.icu.text.Transliterator
import java.text.Normalizer

/**
 * Script-aware fuzzy matching for music tags.
 *
 * **Jaro–Winkler** — Latin names/titles. Jaro is the mean of matching-char
 * rates in both strings plus the non-transposed match rate. Matches only count
 * inside a sliding window of `max(len)/2 - 1`. Winkler then boosts strings that
 * share a prefix (up to 4 chars, scale 0.1). Great for "Seether"/"Seeter" and
 * "Fray"/"Frat" at the end of a long title. Bad for CJK: one kanji is a whole
 * morpheme, prefix boost is Latin-centric, and a 4-character title with one
 * typo is already 25% different.
 *
 * **Levenshtein** — edit count. We keep it as a *cap* ("at most 2 edits") so
 * Trench/Breach (2 edits, 6 letters) stay distinct.
 *
 * **N-gram Jaccard** — best default for CJK. Each string → set of character
 * n-grams; score is `|A∩B| / |A∪B|`. Unigrams treat each Han/Kana/Hangul
 * character as a token (the usual CJK indexer). Bigrams catch local typos
 * without needing spaces. Same metric works for Latin ("oh ms believer").
 *
 * **Folds before compare** (not stored in catalog keys):
 * - NFKC, Latin-ASCII (Ø→O, é→e)
 * - Katakana→Hiragana so ナイトコード ≡ ないとこーど
 * - Traditional→Simplified Han so 周杰倫 ≡ 周杰伦
 * - Hangul NFD (jamo) so syllable-level typos compare
 *
 * Libraries considered, not taken:
 * - `com.aallam.similarity:string-similarity-kotlin` — KMP port of tdebatty,
 *   last release 0.1.0 / years stale.
 * - `info.debatty:java-string-similarity` — same algorithms, JVM-only.
 * - Apache Commons Text — maintained, JVM-only, KMP later would fork anyway.
 * In-tree + ICU transliterators stay KMP-movable (swap ICU for a expect/actual).
 */
object StringSimilarity {

    enum class Kind { ALBUM, TITLE, ARTIST }

    fun likelySame(a: String, b: String, kind: Kind): Boolean {
        if (a == b) return true
        val fa = foldCompare(a)
        val fb = foldCompare(b)
        if (fa.isEmpty() || fb.isEmpty()) return false
        if (fa == fb) return true

        val pa = codePoints(fa)
        val pb = codePoints(fb)
        val minCp = minOf(pa.size, pb.size)
        val maxCp = maxOf(pa.size, pb.size)
        if (minCp == 0) return false
        if (maxCp - minCp > 4 && maxCp > 8) return false

        val cjk = isCjkHeavy(pa) || isCjkHeavy(pb)
        val levCap = if (cjk) 2 else 2
        val lev = editDistanceCodePoints(pa, pb, levCap + 1)
        val jw = jaroWinkler(pa, pb)
        val jac1 = ngramJaccard(pa, pb, 1)
        val jac2 = if (minCp >= 2) ngramJaccard(pa, pb, 2) else jac1

        if (cjk) {
            return when (kind) {
                Kind.ARTIST -> (jac1 >= 0.85 && lev <= 1) || jw >= 0.95
                Kind.ALBUM, Kind.TITLE ->
                    jac1 >= 0.80 && lev <= 1 ||
                        jac2 >= 0.75 ||
                        (jw >= 0.90 && lev <= 1)
            }
        }

        return when (kind) {
            Kind.ALBUM ->
                minCp >= 12 && jw >= 0.93 && lev <= 2
            Kind.TITLE ->
                (minCp >= 6 && jw >= 0.90 && lev <= 2) ||
                    (minCp >= 14 && jw >= 0.92)
            Kind.ARTIST ->
                minCp >= 4 && jw >= 0.96 && lev <= 2
        }
    }

    fun foldCompare(value: String): String {
        var t = value.trim().lowercase()
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        t = transliterate(t, "Traditional-Simplified")
        t = katakanaToHiragana(t)
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
        t = t.replace(Regex("\\p{M}+"), "")
        t = transliterate(t, "Latin-ASCII")
        t = t.replace(Regex("[\\s\\p{Punct}]+"), " ").trim()
        return t
    }

    private fun transliterate(value: String, id: String): String = try {
        Transliterator.getInstance(id).transliterate(value)
    } catch (_: Exception) {
        value
    }

    private fun katakanaToHiragana(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val folded = if (cp in 0x30A1..0x30F6) cp - 0x60 else cp
            sb.appendCodePoint(folded)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isCjkHeavy(cps: IntArray): Boolean {
        if (cps.isEmpty()) return false
        var n = 0
        for (cp in cps) {
            val script = Character.UnicodeScript.of(cp)
            if (script == Character.UnicodeScript.HAN ||
                script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA ||
                script == Character.UnicodeScript.HANGUL ||
                script == Character.UnicodeScript.BOPOMOFO
            ) {
                n++
            }
        }
        return n * 2 >= cps.size
    }

    private fun codePoints(s: String): IntArray {
        val n = s.codePointCount(0, s.length)
        val out = IntArray(n)
        var i = 0
        var p = 0
        while (p < s.length) {
            val cp = s.codePointAt(p)
            out[i++] = cp
            p += Character.charCount(cp)
        }
        return out
    }

    /** Jaro–Winkler similarity in `0f..1f`. */
    fun jaroWinkler(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.contentEquals(b)) return 1f
        val jaro = jaro(a, b)
        if (jaro < 0.7f) return jaro
        var prefix = 0
        val limit = minOf(4, a.size, b.size)
        while (prefix < limit && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1f * (1f - jaro)
    }

    private fun jaro(a: IntArray, b: IntArray): Float {
        val s1: IntArray
        val s2: IntArray
        if (a.size >= b.size) {
            s1 = a
            s2 = b
        } else {
            s1 = b
            s2 = a
        }
        val matchWindow = maxOf(s1.size / 2 - 1, 0)
        val s1Match = BooleanArray(s1.size)
        val s2Match = BooleanArray(s2.size)
        var matches = 0
        for (i in s1.indices) {
            val lo = maxOf(0, i - matchWindow)
            val hi = minOf(i + matchWindow + 1, s2.size)
            for (j in lo until hi) {
                if (s2Match[j] || s1[i] != s2[j]) continue
                s1Match[i] = true
                s2Match[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0f
        var k = 0
        var trans = 0
        for (i in s1.indices) {
            if (!s1Match[i]) continue
            while (!s2Match[k]) k++
            if (s1[i] != s2[k]) trans++
            k++
        }
        val m = matches.toFloat()
        return (m / s1.size + m / s2.size + (m - trans / 2f) / m) / 3f
    }

    fun ngramJaccard(a: IntArray, b: IntArray, n: Int): Float {
        if (n <= 0) return 0f
        if (a.contentEquals(b)) return 1f
        val ga = ngrams(a, n)
        val gb = ngrams(b, n)
        if (ga.isEmpty() && gb.isEmpty()) return 1f
        if (ga.isEmpty() || gb.isEmpty()) return 0f
        var inter = 0
        val keys = ga.keys + gb.keys
        var union = 0
        for (g in keys) {
            val ca = ga[g] ?: 0
            val cb = gb[g] ?: 0
            inter += minOf(ca, cb)
            union += maxOf(ca, cb)
        }
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun ngrams(cps: IntArray, n: Int): Map<Long, Int> {
        if (cps.size < n) {
            if (cps.isEmpty()) return emptyMap()
            var h = 0L
            for (cp in cps) h = h * 1_000_003L + cp
            return mapOf(h to 1)
        }
        val out = HashMap<Long, Int>(cps.size)
        for (i in 0..cps.size - n) {
            var h = 0L
            for (j in 0 until n) h = h * 1_000_003L + cps[i + j]
            out[h] = (out[h] ?: 0) + 1
        }
        return out
    }

    private fun editDistanceCodePoints(a: IntArray, b: IntArray, max: Int): Int {
        if (a.contentEquals(b)) return 0
        if (kotlin.math.abs(a.size - b.size) > max) return max + 1
        val m = b.size
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..a.size) {
            cur[0] = i
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m]
    }
}
