package capital.yuri.yuriplayer.data

/**
 * Builds the Android activity / recents title from the current song.
 *
 * Tokens (case-sensitive):
 * - `{title}` / `{song}` — song title
 * - `{artist}` — track artist
 * - `{album}` — album name
 * - `{albumArtist}` — album artist
 *
 * Default: `YuriPlayer: {title} by {artist}`
 * Settings can override [format] later; empty / unknown tokens become "".
 */
object ActivityTitleFormat {

    const val DEFAULT = "YuriPlayer: {title} by {artist}"

    /** In-memory until Settings + prefs land. */
    @Volatile
    var format: String = DEFAULT

    fun format(song: Song?): String {
        if (song == null) return "YuriPlayer"
        val template = format.ifBlank { DEFAULT }
        return template
            .replace("{title}", song.displayTitle)
            .replace("{song}", song.displayTitle)
            .replace("{artist}", song.displayArtist)
            .replace("{album}", song.displayAlbum)
            .replace("{albumArtist}", song.displayAlbumArtist)
            .trim()
            .ifBlank { "YuriPlayer" }
    }
}
