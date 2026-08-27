package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.Song

/**
 * Library search matching used by desktop Explore (and shared later).
 * Folds stylized letters (ø → o), ignores extra punctuation/spacing, and
 * requires every query token to appear somewhere in title/artist/album/path.
 */
fun Song.matchesQuery(query: String): Boolean {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return false
    val hay = foldSearch(
        listOfNotNull(
            title, artist, albumArtist, album, genre, path, contentUri, displayTitle, displayArtist, displayAlbum
        ).joinToString(" ")
    )
    return tokens.all { hay.contains(it) }
}

fun String.matchesSearch(query: String): Boolean {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return false
    val hay = foldSearch(this)
    return tokens.all { hay.contains(it) }
}

fun searchTokens(query: String): List<String> =
    foldSearch(query).split(' ').filter { it.isNotEmpty() }

fun foldSearch(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        when (ch) {
            'ø', 'Ø', 'ó', 'ò', 'ö', 'ô', 'õ', 'Ó', 'Ò', 'Ö', 'Ô', 'Õ' -> sb.append('o')
            'á', 'à', 'ä', 'â', 'ã', 'å', 'Á', 'À', 'Ä', 'Â', 'Ã', 'Å' -> sb.append('a')
            'é', 'è', 'ë', 'ê', 'É', 'È', 'Ë', 'Ê' -> sb.append('e')
            'í', 'ì', 'ï', 'î', 'Í', 'Ì', 'Ï', 'Î' -> sb.append('i')
            'ú', 'ù', 'ü', 'û', 'Ú', 'Ù', 'Ü', 'Û' -> sb.append('u')
            'ñ', 'Ñ' -> sb.append('n')
            'ç', 'Ç' -> sb.append('c')
            'ß' -> sb.append("ss")
            'æ', 'Æ' -> sb.append("ae")
            'œ', 'Œ' -> sb.append("oe")
            '&' -> sb.append(" and ")
            else -> {
                val lower = ch.lowercaseChar()
                if (lower.isLetterOrDigit()) sb.append(lower)
                else if (lower.isWhitespace() || lower == '/' || lower == '\\' || lower == '-' || lower == '_') {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                }
            }
        }
        i++
    }
    return sb.toString().trim()
}
