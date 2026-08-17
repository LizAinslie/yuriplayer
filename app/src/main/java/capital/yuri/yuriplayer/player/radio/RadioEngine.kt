package capital.yuri.yuriplayer.player.radio

import android.util.Log
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.ColdSourceType
import capital.yuri.yuriplayer.player.RepeatMode

/**
 * Owns active radio session + algorithm dispatch.
 *
 * On-device by default. Swap [ReleaseCatalog] / add a remote [RadioAlgorithm]
 * later without touching QueueManager — this is the boundary for offload.
 */
class RadioEngine(
    private val library: LibraryIndex,
    private val settings: LibrarySettings,
    private val playbackAlgo: RadioPlaybackAlgorithm = RadioPlaybackAlgorithm(),
    private val poolAlgo: ReleasePoolAlgorithm = ReleasePoolAlgorithm()
) {
    private val catalog = ReleaseCatalog { library.albums(taggedOnly = true) }
    private val memory = RecentReleaseMemory()

    /** Which strategy runs on queue exhaust when auto-play is on. */
    @Volatile
    var activeAlgorithm: RadioAlgorithmId = RadioAlgorithmId.PLAYBACK

    fun configurePlayback(config: RadioPlaybackConfig) {
        playbackAlgo.config = config
    }

    fun configurePool(config: ReleasePoolConfig) {
        poolAlgo.config = config
    }

    /** Start a release-pool radio from a playlist’s artists. */
    fun startPlaylistRadio(songs: List<Song>) {
        val cfg = PlaylistRadioSeed.fromTracks(songs)
        configurePool(cfg)
        activeAlgorithm = RadioAlgorithmId.RELEASE_POOL
        Log.i(TAG, "playlist radio artists=${cfg.artistKeys.size}")
    }

    fun startArtistRadio() {
        activeAlgorithm = RadioAlgorithmId.PLAYBACK
    }

    fun noteSource(source: ColdSource?) {
        if (source?.type != ColdSourceType.ALBUM) return
        val id = source.id.takeIf { it.isNotBlank() } ?: return
        // Kind unknown until we resolve album; treat as UNKNOWN for global only.
        memory.note(id, ReleaseKind.UNKNOWN)
    }

    fun notePick(pick: RadioPick) {
        memory.note(ReleaseClassifier.releaseKey(pick.album), pick.kind)
    }

    /**
     * Called from auto-play / exhaust path.
     * @return null if disabled, repeating, or no candidate.
     */
    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): RadioPick? {
        if (!settings.isAutoPlayRecommendedEnabled()) {
            Log.d(TAG, "skip — auto-play setting off")
            return null
        }
        if (repeatMode != RepeatMode.OFF) {
            Log.d(TAG, "skip — repeatMode=$repeatMode")
            return null
        }

        val focus = resolveFocusArtists(seedSong, finishedSource)
        val ctx = RadioContext(
            seedSong = seedSong,
            finishedSource = finishedSource,
            repeatMode = repeatMode,
            focusArtistKeys = focus
        )

        if (finishedSource?.type == ColdSourceType.ALBUM) {
            memory.note(finishedSource.id, ReleaseKind.UNKNOWN)
        }

        val algo = when (activeAlgorithm) {
            RadioAlgorithmId.PLAYBACK -> playbackAlgo
            RadioAlgorithmId.RELEASE_POOL -> poolAlgo
        }

        val pick = algo.pick(catalog, ctx, memory)
        if (pick != null) {
            notePick(pick)
            Log.i(
                TAG,
                "pick '${pick.album.displayName}' via ${pick.algorithmId} " +
                    "(${pick.reason})"
            )
        }
        return pick
    }

    private fun resolveFocusArtists(seed: Song?, source: ColdSource?): Set<String> {
        val out = linkedSetOf<String>()
        seed?.effectiveAlbumArtist?.let { artistKey(it)?.let(out::add) }
        seed?.artist?.let { artistKey(it)?.let(out::add) }
        seed?.creditArtists?.forEach { artistKey(it)?.let(out::add) }
        when (source?.type) {
            ColdSourceType.ARTIST -> {
                artistKey(source.title)?.let(out::add)
                artistKey(source.id)?.let(out::add)
            }
            ColdSourceType.ALBUM -> {
                val left = source.id.substringBefore('|', "").trim()
                artistKey(left)?.let(out::add)
            }
            else -> Unit
        }
        return out
    }

    companion object {
        private const val TAG = "YuriPlayer.Radio"
    }
}
