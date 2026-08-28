package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.RepeatMode
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

/** How shuffle fills the radio cold queue. */
@Serializable
enum class RadioShuffleUnit {
    /** Random tracks from the whole pool (default). */
    SONGS,
    /** Random order of whole LP/EP/Single blocks; tracks stay in release order. */
    RELEASES
}

/**
 * Per radio-source preferences (artist / album / playlist station).
 *
 * Defaults: shuffle ON with [RadioShuffleUnit.SONGS]. Ordered whole-release
 * mode remains available by turning shuffle off.
 *
 * Discovery flags: combine any set of sources for similar artists/songs.
 */
@Serializable
data class RadioSourcePrefs(
    /**
     * true  → use [shuffleUnit] to fill cold up to [maxRadioQueue].
     * false → whole LP/EP/Single blocks in year-desc order (legacy ordered mode).
     */
    val shuffle: Boolean = true,
    val shuffleUnit: RadioShuffleUnit = RadioShuffleUnit.SONGS,
    val maxRadioQueue: Int = DEFAULT_MAX_RADIO_QUEUE,
    /** On-device catalog / already-scanned sources. */
    val useLibraryDiscovery: Boolean = true,
    /** Jellyfin Instant Mix when a matching item exists on an enabled server. */
    val useJellyfinInstantMix: Boolean = false,
    /** Subsonic / OpenSubsonic similar-songs / similar-artists. */
    val useSubsonicSimilar: Boolean = false,
    /**
     * monochrome.tf community hifi-api recommendations
     * (`/recommendations`, `/artist/similar`, `/mix`) — discovery only.
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
