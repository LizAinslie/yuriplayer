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

/**
 * A planned radio segment: the songs RadioEngine wants in the cold queue.
 * Queue UI shows this list as the radio cold queue.
 */
data class RadioBatch(
    val songs: List<Song>,
    val source: ColdSource,
    val session: RadioSession,
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
    val allowExternalFetch: Boolean = false,
    val avoidRecentPerKind: Int = 1
)

/**
 * Per radio-source preferences (artist / album / playlist station).
 *
 * Discovery flags control whether external servers can inject similar
 * artists / songs / genres beyond the on-device catalog.
 *
 * - Library discovery is always the baseline (local + already-scanned sources).
 * - Jellyfin Instant Mix uses the official SDK InstantMix endpoint when a
 *   matching item exists on an enabled Jellyfin instance.
 * - Subsonic/OpenSubsonic similar-songs is reserved.
 * - Monochrome (monochrome.tf / community hifi-api) is recommendation-oriented
 *   (Tidal metadata + /recommendations, /artist/similar, /mix) — not a library
 *   store. Flag reserved for a thin discovery client later.
 */
@Serializable
data class RadioSourcePrefs(
    /**
     * true  → random songs; restock cold to [maxRadioQueue] as tracks finish.
     * false → whole LP/EP/Single blocks until the last block reaches ≥ maxRadioQueue.
     */
    val shuffle: Boolean = false,
    val maxRadioQueue: Int = DEFAULT_MAX_RADIO_QUEUE,
    /** Prefer tracks already indexed in the on-device catalog (always on by default). */
    val useLibraryDiscovery: Boolean = true,
    /**
     * When true and a Jellyfin source is available for the seed, ask the server
     * for Instant Mix / similar items and merge into the radio pool.
     */
    val useJellyfinInstantMix: Boolean = false,
    /** Reserved for Subsonic / OpenSubsonic getSimilarSongs / getSimilarArtists. */
    val useSubsonicSimilar: Boolean = false,
    /**
     * Reserved: monochrome.tf community hifi-api recommendations
     * (`/recommendations`, `/artist/similar`, `/album/similar`, `/mix`).
     * Discovery only — not a primary library source.
     */
    val useMonochromeDiscovery: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_RADIO_QUEUE = 50
    }
}

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

@Serializable
data class RadioSession(
    val kind: RadioSessionKind,
    val displayName: String,
    val algorithmId: RadioAlgorithmId,
    val seedId: String? = null,
    val seedTitle: String? = null,
    val active: Boolean = true,
    val prefs: RadioSourcePrefs = RadioSourcePrefs()
)
