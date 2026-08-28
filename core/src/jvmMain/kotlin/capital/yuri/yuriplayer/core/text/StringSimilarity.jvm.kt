package capital.yuri.yuriplayer.core.text

import java.text.Normalizer

internal actual fun foldCompare(s: String): String {
    var t = s.trim().lowercase()
    t = Normalizer.normalize(t, Normalizer.Form.NFKC)
    t = katakanaToHiragana(t)
    t = Normalizer.normalize(t, Normalizer.Form.NFD)
    t = t.replace(Regex("\\p{M}+"), "")
    t = latinAscii(t)
    t = t.replace(Regex("[\\s\\p{Punct}]+"), " ").trim()
    return t
}

internal actual fun isCjkCodePoint(cp: Int): Boolean =
    cp in 0x3400..0x4DBF || // CJK Extension A
        cp in 0x4E00..0x9FFF || // CJK Unified Ideographs
        cp in 0x3040..0x30FF || // Hiragana + Katakana
        cp in 0x31F0..0x31FF || // Katakana phonetic extensions
        cp in 0xAC00..0xD7AF || // Hangul syllables
        cp in 0x3100..0x312F || // Bopomofo
        cp in 0x31A0..0x31BF // Bopomofo Extended
