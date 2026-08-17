package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.RepeatMode
import kotlinx.serialization.Serializable

/** Coarse release shape used for cooldown / weighting. */
enum class ReleaseKind {
    LP,
    EP,
    SINGLE,
    UNKNOWN
}

/**
 * Something a radio can queue as the next cold source.
 * Today always a full release (album/EP/single); later may be a track mix.
 */
data class RadioPick(
    val album: AlbumItem,
    val source: ColdSource,
    val kind: ReleaseKind,
    val algorithmId: String,
    val reason: String = ""
)

/** Snapshot of “what just finished” so algorithms can bias the next pick. */
data class RadioContext(
    val seedSong: Song?,
    val finishedSource: ColdSource?,
    val repeatMode: RepeatMode,
    /** Normalized artist keys the user is currently “in” (seed + source). */
    val focusArtistKeys: Set<String> = emptySet(),
    /** Optional genre focus (empty = any). */
    val focusGenreKeys: Set<String> = emptySet()
)

/**
 * Tunables for [RadioPlaybackAlgorithm]. Persisted later via Settings.
 *
 * Entropy knobs (see RadioPlaybackAlgorithm KDoc):
 * - Hard cooldowns per kind avoid immediate LP→same LP
 * - Soft weights let older plays still appear, just less often
 * - Type weights bias LP vs EP vs Single
 * - sameArtistBias 1.0 = pure artist radio; lower mixes in pool artists later
 */
@Serializable
data class RadioPlaybackConfig(
    /** How many recent LPs to hard-exclude. */
    val avoidRecentLps: Int = 1,
    val avoidRecentEps: Int = 1,
    val avoidRecentSingles: Int = 1,
    /** Extra global release keys to hard-exclude regardless of kind. */
    val globalRecentExclude: Int = 1,
    /** Relative sampling weights (higher = more often). */
    val weightLp: Float = 1.0f,
    val weightEp: Float = 1.0f,
    val weightSingle: Float = 0.85f,
    /**
     * Soft penalty: each step back in recent history multiplies weight by this
     * (0.55 ≈ half weight after ~1 prior play). 1f = no soft penalty.
     */
    val softPenaltyFactor: Float = 0.55f,
    /** Max recent keys kept for soft penalty (ring buffer). */
    val softHistorySize: Int = 12,
    /**
     * 1f = only same artist as seed. Values &lt; 1 reserved for future
     * cross-artist hops via ReleasePoolAlgorithm.
     */
    val sameArtistBias: Float = 1.0f
)

/** Tunables for [ReleasePoolAlgorithm]. */
@Serializable
data class ReleasePoolConfig(
    val artistKeys: List<String> = emptyList(),
    val genreKeys: List<String> = emptyList(),
    val includeLps: Boolean = true,
    val includeEps: Boolean = true,
    val includeSingles: Boolean = true,
    /**
     * When true, algorithm may request tracks not in the local library
     * from preferred external sources. **Stub / privacy gate — always off.**
     */
    val allowExternalFetch: Boolean = false,
    val avoidRecentPerKind: Int = 1
)

@Serializable
enum class RadioAlgorithmId {
    /** Same-artist continuous radio after a queue ends. */
    PLAYBACK,
    /** User-built artist/genre pool. */
    RELEASE_POOL
}
