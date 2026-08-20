package capital.yuri.yuriplayer.data.theme

/**
 * Where a content palette is applied.
 *
 * [COVER] — now playing, album pages, playlist pages.
 * [BANNER] — artist page header / banner.
 */
enum class ArtColorSurface(val id: String, val displayName: String) {
    COVER("cover", "Cover"),
    BANNER("banner", "Banner")
}

/**
 * Which Palette swatch family seeds the dynamic colors for a [ArtColorSurface].
 */
enum class ArtColorVariant(
    val id: String,
    val displayName: String,
    val description: String
) {
    AUTO(
        "auto",
        "Auto",
        "Dark muted stage with a punchy vibrant accent"
    ),
    VIBRANT(
        "vibrant",
        "Vibrant",
        "Saturated colors from the artwork"
    ),
    MUTED(
        "muted",
        "Muted",
        "Softer, desaturated artwork colors"
    ),
    DARK_MUTED(
        "dark_muted",
        "Dark muted",
        "Deepest muted tones from the artwork"
    ),
    DOMINANT(
        "dominant",
        "Dominant",
        "The most common color in the artwork"
    );

    companion object {
        fun fromId(id: String?): ArtColorVariant =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}
