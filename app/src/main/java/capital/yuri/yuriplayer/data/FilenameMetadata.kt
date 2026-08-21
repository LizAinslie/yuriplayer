package capital.yuri.yuriplayer.data

/**
 * Tags inferred from a file name / parent folder when the embedded tag is empty.
 *
 * Patterns:
 *  - `08. Title` / `08 - Title` / `08_Title`
 *  - `1-08 Title` / `01-08. Title` (disc-track)
 *  - `CD2 - 08 Title` / `Disc 2 - 08. Title`
 *  - parent folder `CD2` / `Disc 2`
 */
data class FilenameMetadata(
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val title: String? = null
) {
    val isEmpty: Boolean
        get() = trackNumber == null && discNumber == null && title.isNullOrBlank()
}

object FilenameMetadataParser {

    private val DISC_TRACK_TITLE = Regex(
        """^(?:(?:CD|Disc|Disk)\s*)?(\d{1,2})\s*[-.]\s*(\d{1,3})\s*[-._)\s]+\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val CD_PREFIX_TRACK = Regex(
        """^(?:CD|Disc|Disk)\s*(\d{1,2})\s*[-._ ]+\s*(\d{1,3})\s*[-._)\s]+\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val TRACK_TITLE = Regex(
        """^(\d{1,3})\s*[-._)\s]+\s*(.+)$"""
    )
    private val DISC_FOLDER = Regex(
        """^(?:CD|Disc|Disk)\s*(\d{1,2})$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(path: String?): FilenameMetadata {
        if (path.isNullOrBlank()) return FilenameMetadata()
        val slash = path.replace('\\', '/')
        val fileName = slash.substringAfterLast('/').substringBeforeLast('.')
        val parent = slash.substringBeforeLast('/').substringAfterLast('/')

        var disc: Int? = null
        var track: Int? = null
        var title: String? = null

        CD_PREFIX_TRACK.matchEntire(fileName)?.let { m ->
            disc = m.groupValues[1].toIntOrNull()
            track = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            title = cleanTitle(m.groupValues[3])
        } ?: DISC_TRACK_TITLE.matchEntire(fileName)?.let { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            if (a != null && a in 1..99 && b != null) {
                disc = a
                track = b
                title = cleanTitle(m.groupValues[3])
            }
        } ?: TRACK_TITLE.matchEntire(fileName)?.let { m ->
            track = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..999 }
            title = cleanTitle(m.groupValues[2])
        }

        if (disc == null) {
            disc = DISC_FOLDER.matchEntire(parent.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return FilenameMetadata(
            trackNumber = track,
            discNumber = disc?.takeIf { it > 0 },
            title = title
        )
    }

    private fun cleanTitle(raw: String): String? {
        val t = raw.trim().trim('.', '-', '_').trim()
        return t.takeIf { it.length >= 1 }
    }
}
