package capital.yuri.yuriplayer.core.player

import kotlinx.serialization.Serializable

/**
 * The context the cold queue is currently playing through (an album, playlist,
 * artist, radio station, or a one-off "songs" list). Shared so album/artist/
 * playlist pages can light up their play/pause button and the queue UI can name
 * the source.
 */
@Serializable
enum class ColdSourceType { ALBUM, PLAYLIST, ARTIST, SONGS, RADIO, UNKNOWN }

@Serializable
data class ColdSource(
    val type: ColdSourceType,
    val id: String,
    val title: String? = null
) {
    fun matches(type: ColdSourceType, id: String): Boolean =
        this.type == type && this.id.equals(id, ignoreCase = true)
}
