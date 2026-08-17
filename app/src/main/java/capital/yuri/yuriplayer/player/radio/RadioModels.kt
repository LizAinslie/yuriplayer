package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.RepeatMode
import kotlinx.serialization.Serializable

enum class ReleaseKind {
    LP,
    EP,
    SINGLE,
    UNKNOWN
}

data class RadioPick(
    val album: AlbumItem,
    val source: ColdSource,
    val kind: ReleaseKind,
    val algorithmId: String,
    val reason: String = ""
)

data class RadioContext(
    val seedSong: Song?,
    val finishedSource: ColdSource?,
    val repeatMode: RepeatMode,
    val focusArtistKeys: Set<String> = emptySet(),
    val focusGenreKeys: Set<String> = emptySet()
)

@Serializable
data class RadioPlaybackConfig(
    val avoidRecentLps: Int = 1,
    val avoidRecentEps: Int = 1,
    val avoidRecentSingles: Int = 1,
    val globalRecentExclude: Int = 1,
    val weightLp: Float = 1.0f,
    val weightEp: Float = 1.0f,
    val weightSingle: Float = 0.85f,
    val softPenaltyFactor: Float = 0.55f,
    val softHistorySize: Int = 12,
    val sameArtistBias: Float = 1.0f
)

@Serializable
data class ReleasePoolConfig(
    val artistKeys: List<String> = emptyList(),
    val genreKeys: List<String> = emptyList(),
    val includeLps: Boolean = true,
    val includeEps: Boolean = true,
    val includeSingles: Boolean = true,
    /** Privacy gate — external fetch stub stays off until user opts in. */
    val allowExternalFetch: Boolean = false,
    val avoidRecentPerKind: Int = 1
)

@Serializable
enum class RadioAlgorithmId {
    PLAYBACK,
    RELEASE_POOL
}

@Serializable
enum class RadioSessionKind {
    ARTIST,
    ALBUM,
    PLAYLIST,
    CUSTOM
}

/**
 * Active radio identity for queue UI + exhaust path.
 *
 * Shuffle policy: shuffle only reorders tracks **inside the current segment**
 * (the release loaded into cold queue). The next radio pick is always chosen by
 * the algorithm; turning shuffle on/off does not re-roll that pick. When a new
 * segment loads and shuffle is on, that segment starts shuffled.
 */
@Serializable
data class RadioSession(
    val kind: RadioSessionKind,
    val displayName: String,
    val algorithmId: RadioAlgorithmId,
    val seedId: String? = null,
    val seedTitle: String? = null,
    val active: Boolean = true
)
