package capital.yuri.yuriplayer.core.text

import android.icu.text.Transliterator
import android.os.Build
import java.text.Normalizer

internal actual fun foldCompare(s: String): String {
    var t = s.trim().lowercase()
    t = Normalizer.normalize(t, Normalizer.Form.NFKC)
    t = transliterate(t, "Traditional-Simplified")
    t = katakanaToHiragana(t)
    t = Normalizer.normalize(t, Normalizer.Form.NFD)
    t = t.replace(Regex("\\p{M}+"), "")
    t = transliterate(t, "Latin-ASCII")
    t = latinAscii(t)
    t = t.replace(Regex("[\\s\\p{Punct}]+"), " ").trim()
    return t
}

private fun transliterate(value: String, id: String): String = try {
    if (Build.VERSION.SDK_INT >= 29) {
        Transliterator.getInstance(id).transliterate(value)
    } else {
        value
    }
} catch (_: Exception) {
    value
}

internal actual fun isCjkCodePoint(cp: Int): Boolean {
    val script = Character.UnicodeScript.of(cp)
    return script == Character.UnicodeScript.HAN ||
        script == Character.UnicodeScript.HIRAGANA ||
        script == Character.UnicodeScript.KATAKANA ||
        script == Character.UnicodeScript.HANGUL ||
        script == Character.UnicodeScript.BOPOMOFO
}
