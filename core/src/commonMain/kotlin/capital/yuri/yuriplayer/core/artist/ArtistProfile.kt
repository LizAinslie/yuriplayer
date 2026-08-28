package capital.yuri.yuriplayer.core.artist

import kotlinx.serialization.Serializable

enum class ArtistImageKind { PROFILE, BANNER }

@Serializable
data class ArtistImageCandidate(
    val url: String,
    val sourceId: String,
    val label: String = sourceId,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class ArtistProfile(
    val artistKey: String,
    val displayName: String,
    val bio: String? = null,
    val imageUri: String? = null,
    val bannerUri: String? = null,
    val genres: List<String> = emptyList(),
    val source: String = "local",
    val updatedAtMs: Long = 0L,
    val bannerCleared: Boolean = false
)

fun artistKey(name: String?): String? {
    val t = name?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: return null
    return t.takeIf { it.isNotEmpty() }
}
