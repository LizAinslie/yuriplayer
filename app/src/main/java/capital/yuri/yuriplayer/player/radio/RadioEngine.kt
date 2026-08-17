package capital.yuri.yuriplayer.player.radio

import android.util.Log
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.player.ColdSource
import capital.yuri.yuriplayer.player.ColdSourceType
import capital.yuri.yuriplayer.player.RepeatMode

/**
 * Owns active radio session + algorithm dispatch + next-release prefetch.
 */
class RadioEngine(
    private val library: LibraryIndex,
    private val settings: LibrarySettings,
    private val playbackAlgo: RadioPlaybackAlgorithm = RadioPlaybackAlgorithm(),
    private val poolAlgo: ReleasePoolAlgorithm = ReleasePoolAlgorithm()
) {
    private val catalog = ReleaseCatalog { library.albums(taggedOnly = true) }
    private val memory = RecentReleaseMemory()

    @Volatile
    var activeAlgorithm: RadioAlgorithmId = RadioAlgorithmId.PLAYBACK
        private set

    @Volatile
    var session: RadioSession? = null
        private set

    /** Prefetched next release for queue UI / seamless advance. */
    @Volatile
    var upcomingPick: RadioPick? = null
        private set

    fun configurePlayback(config: RadioPlaybackConfig) {
        playbackAlgo.config = config
    }

    fun configurePool(config: ReleasePoolConfig) {
        poolAlgo.config = config
    }

    fun startAlbumRadio(album: AlbumItem): RadioSession {
        val (cfg, sess) = AlbumRadioSeed.fromAlbum(album)
        configurePool(cfg)
        activeAlgorithm = RadioAlgorithmId.RELEASE_POOL
        session = sess
        memory.note(ReleaseClassifier.releaseKey(album), ReleaseClassifier.kindOf(album))
        upcomingPick = null
        Log.i(TAG, "album radio '${sess.displayName}' artists=${cfg.artistKeys.size}")
        return sess
    }

    fun startArtistRadio(artistName: String): RadioSession {
        val sess = AlbumRadioSeed.artistSession(artistName)
        activeAlgorithm = RadioAlgorithmId.PLAYBACK
        session = sess
        upcomingPick = null
        Log.i(TAG, "artist radio '${sess.displayName}'")
        return sess
    }

    fun startPlaylistRadio(songs: List<Song>, playlistName: String?): RadioSession {
        val (cfg, sess) = AlbumRadioSeed.fromTracksForPlaylist(songs, playlistName)
        configurePool(cfg)
        activeAlgorithm = RadioAlgorithmId.RELEASE_POOL
        session = sess
        upcomingPick = null
        Log.i(TAG, "playlist radio '${sess.displayName}' artists=${cfg.artistKeys.size}")
        return sess
    }

    fun stopRadio() {
        session = null
        upcomingPick = null
        Log.i(TAG, "radio stopped")
    }

    fun noteSource(source: ColdSource?) {
        if (source?.type != ColdSourceType.ALBUM && source?.type != ColdSourceType.RADIO) return
        val id = source.id.takeIf { it.isNotBlank() } ?: return
        memory.note(id, ReleaseKind.UNKNOWN)
    }

    fun notePick(pick: RadioPick) {
        memory.note(ReleaseClassifier.releaseKey(pick.album), pick.kind)
    }

    /**
     * Next segment for radio / auto-play.
     * Explicit [session] continues even if auto-play setting is off.
     * Passive auto-play still requires the setting.
     */
    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): RadioPick? {
        val radioActive = session?.active == true
        if (!radioActive && !settings.isAutoPlayRecommendedEnabled()) {
            Log.d(TAG, "skip — no session & auto-play off")
            return null
        }
        if (repeatMode != RepeatMode.OFF) {
            Log.d(TAG, "skip — repeatMode=$repeatMode")
            return null
        }

        // Prefer prefetched pick if still valid
        upcomingPick?.let { pre ->
            upcomingPick = null
            notePick(pre)
            Log.i(TAG, "using prefetched '${pre.album.displayName}'")
            prefetchAfter(pre, seedSong, finishedSource, repeatMode)
            return pre
        }

        val pick = pickInternal(seedSong, finishedSource, repeatMode) ?: return null
        notePick(pick)
        prefetchAfter(pick, seedSong, finishedSource, repeatMode)
        return pick
    }

    /** Warm the next release for queue "Up next". */
    fun prefetchUpcoming(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ) {
        if (session?.active != true && !settings.isAutoPlayRecommendedEnabled()) return
        if (repeatMode != RepeatMode.OFF) return
        if (upcomingPick != null) return
        upcomingPick = pickInternal(seedSong, finishedSource, repeatMode)
        Log.i(TAG, "prefetch upcoming='${upcomingPick?.album?.displayName}'")
    }

    fun upcomingSongs(): List<Song> = upcomingPick?.album?.songs.orEmpty()

    private fun prefetchAfter(
        justPicked: RadioPick,
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ) {
        // Exclude the pick we just took via memory already noted
        upcomingPick = pickInternal(
            seedSong = justPicked.album.songs.firstOrNull() ?: seedSong,
            finishedSource = justPicked.source,
            repeatMode = repeatMode
        )
    }

    private fun pickInternal(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): RadioPick? {
        val focus = resolveFocusArtists(seedSong, finishedSource)
        val ctx = RadioContext(
            seedSong = seedSong,
            finishedSource = finishedSource,
            repeatMode = repeatMode,
            focusArtistKeys = focus,
            focusGenreKeys = poolAlgo.config.genreKeys.toSet()
        )

        if (finishedSource?.type == ColdSourceType.ALBUM ||
            finishedSource?.type == ColdSourceType.RADIO
        ) {
            memory.note(finishedSource.id, ReleaseKind.UNKNOWN)
        }

        val algo = when (activeAlgorithm) {
            RadioAlgorithmId.PLAYBACK -> playbackAlgo
            RadioAlgorithmId.RELEASE_POOL -> poolAlgo
        }

        val pick = algo.pick(catalog, ctx, memory)
        if (pick != null) {
            Log.i(
                TAG,
                "pick '${pick.album.displayName}' via ${pick.algorithmId} (${pick.reason})"
            )
        }
        return pick
    }

    private fun resolveFocusArtists(seed: Song?, source: ColdSource?): Set<String> {
        // Prefer explicit pool config when in RELEASE_POOL session
        if (activeAlgorithm == RadioAlgorithmId.RELEASE_POOL &&
            poolAlgo.config.artistKeys.isNotEmpty()
        ) {
            return poolAlgo.config.artistKeys.mapNotNull { artistKey(it) }.toSet()
        }

        val out = linkedSetOf<String>()
        seed?.effectiveAlbumArtist?.let { artistKey(it)?.let(out::add) }
        seed?.artist?.let { artistKey(it)?.let(out::add) }
        seed?.creditArtists?.forEach { artistKey(it)?.let(out::add) }
        when (source?.type) {
            ColdSourceType.ARTIST -> {
                artistKey(source.title)?.let(out::add)
                artistKey(source.id)?.let(out::add)
            }
            ColdSourceType.ALBUM, ColdSourceType.RADIO -> {
                val left = source.id.substringBefore('|', "").trim()
                artistKey(left)?.let(out::add)
            }
            else -> Unit
        }
        session?.seedId?.let { artistKey(it)?.let(out::add) }
        session?.seedTitle?.let { artistKey(it)?.let(out::add) }
        return out
    }

    companion object {
        private const val TAG = "YuriPlayer.Radio"
    }
}
