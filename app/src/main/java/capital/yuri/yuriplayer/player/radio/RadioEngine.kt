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
import kotlin.random.Random

/**
 * Plans the radio cold queue (visible on the queue page) and restocks it.
 *
 * Shuffle OFF: append whole releases until song count ≥ maxRadioQueue.
 * Shuffle ON: random songs; [restock] tops cold up to maxRadioQueue after each finish.
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

    /** Pool of candidate releases for the active session (artist/album/playlist). */
    private var poolReleases: List<AlbumItem> = emptyList()

    fun configurePlayback(config: RadioPlaybackConfig) {
        playbackAlgo.config = config
    }

    fun configurePool(config: ReleasePoolConfig) {
        poolAlgo.config = config
        rebuildPoolFromConfig(config)
    }

    fun startAlbumRadio(album: AlbumItem, prefs: RadioSourcePrefs = RadioSourcePrefs()): RadioSession {
        val (cfg, sess) = AlbumRadioSeed.fromAlbum(album)
        val withPrefs = sess.copy(prefs = prefs)
        configurePool(cfg)
        activeAlgorithm = RadioAlgorithmId.RELEASE_POOL
        session = withPrefs
        memory.note(ReleaseClassifier.releaseKey(album), ReleaseClassifier.kindOf(album))
        Log.i(TAG, "album radio '${withPrefs.displayName}' artists=${cfg.artistKeys.size}")
        return withPrefs
    }

    fun startArtistRadio(artistName: String, prefs: RadioSourcePrefs = RadioSourcePrefs()): RadioSession {
        val sess = AlbumRadioSeed.artistSession(artistName).copy(prefs = prefs)
        activeAlgorithm = RadioAlgorithmId.PLAYBACK
        session = sess
        poolReleases = releasesForArtist(artistName)
        Log.i(TAG, "artist radio '${sess.displayName}' releases=${poolReleases.size}")
        return sess
    }

    fun startPlaylistRadio(
        songs: List<Song>,
        playlistName: String?,
        prefs: RadioSourcePrefs = RadioSourcePrefs()
    ): RadioSession {
        val (cfg, sess) = AlbumRadioSeed.fromTracksForPlaylist(songs, playlistName)
        val withPrefs = sess.copy(prefs = prefs)
        configurePool(cfg)
        activeAlgorithm = RadioAlgorithmId.RELEASE_POOL
        session = withPrefs
        Log.i(TAG, "playlist radio '${withPrefs.displayName}'")
        return withPrefs
    }

    /** Ensure a session exists when passive auto-play kicks in (flags radio mode). */
    fun ensureAutoPlaySession(seedSong: Song?, finishedSource: ColdSource?): RadioSession {
        session?.takeIf { it.active }?.let { return it }
        val artist = seedSong?.effectiveAlbumArtist
            ?: seedSong?.artist
            ?: finishedSource?.title
            ?: finishedSource?.id?.substringBefore('|')
            ?: "Radio"
        return startArtistRadio(artist.trim().ifBlank { "Radio" })
    }

    fun updatePrefs(prefs: RadioSourcePrefs) {
        val s = session ?: return
        session = s.copy(prefs = prefs)
        Log.i(TAG, "prefs shuffle=${prefs.shuffle} max=${prefs.maxRadioQueue}")
    }

    /**
     * Absolute shuffle preference for the active session.
     * Returns null when already at [enabled] (no-op — prevents double planBatch
     * when PlayerController calls queueManager then service→queueManager).
     */
    fun setShufflePrefs(enabled: Boolean): RadioSourcePrefs? {
        val s = session ?: return null
        if (s.prefs.shuffle == enabled) return null
        val next = s.prefs.copy(shuffle = enabled)
        session = s.copy(prefs = next)
        Log.i(TAG, "prefs shuffle=${next.shuffle} max=${next.maxRadioQueue}")
        return next
    }

    fun toggleShufflePrefs(): RadioSourcePrefs? {
        val s = session ?: return null
        return setShufflePrefs(!s.prefs.shuffle)
    }

    fun stopRadio() {
        session = null
        poolReleases = emptyList()
        Log.i(TAG, "radio stopped")
    }

    fun noteSource(source: ColdSource?) {
        if (source?.type != ColdSourceType.ALBUM && source?.type != ColdSourceType.RADIO) return
        val id = source.id.takeIf { it.isNotBlank() } ?: return
        memory.note(id, ReleaseKind.UNKNOWN)
    }

    /**
     * Build a full cold-queue batch for the active session.
     * Used on start and when the planned queue is exhausted.
     */
    fun planBatch(
        seedSong: Song? = null,
        finishedSource: ColdSource? = null,
        excludeKeys: Set<String> = emptySet()
    ): RadioBatch? {
        val sess = session?.takeIf { it.active } ?: return null
        val prefs = sess.prefs
        val max = prefs.maxRadioQueue.coerceIn(1, 500)
        val releases = availableReleases(excludeKeys)
        if (releases.isEmpty()) {
            Log.i(TAG, "planBatch: no releases")
            return null
        }

        val songs = if (prefs.shuffle) {
            planShuffledSongs(releases, max, excludeKeys)
        } else {
            planReleaseBlocks(releases, max, excludeKeys)
        }
        if (songs.isEmpty()) return null

        songs.forEach { s ->
            val k = albumKeyOf(s)
            if (k.isNotBlank()) memory.note(k, ReleaseKind.UNKNOWN)
        }

        val source = ColdSource(
            type = ColdSourceType.RADIO,
            id = sess.seedId ?: sess.displayName,
            title = sess.displayName
        )
        Log.i(
            TAG,
            "planBatch '${sess.displayName}' songs=${songs.size} " +
                "shuffle=${prefs.shuffle} max=$max"
        )
        return RadioBatch(
            songs = songs,
            source = source,
            session = sess,
            reason = if (prefs.shuffle) "shuffle-fill" else "release-blocks"
        )
    }

    /**
     * After a track is consumed in shuffle radio, return songs to append so
     * cold size approaches maxRadioQueue. Empty if already full or not shuffle.
     */
    fun restockSongs(
        currentColdSize: Int,
        alreadyQueuedKeys: Set<String>
    ): List<Song> {
        val sess = session?.takeIf { it.active } ?: return emptyList()
        if (!sess.prefs.shuffle) return emptyList()
        val max = sess.prefs.maxRadioQueue.coerceIn(1, 500)
        val need = max - currentColdSize
        if (need <= 0) return emptyList()

        val pool = songPool(availableReleases(emptySet()))
            .filter { songKey(it) !in alreadyQueuedKeys }
        if (pool.isEmpty()) return emptyList()

        val pick = pool.shuffled(Random(System.nanoTime())).take(need)
        Log.i(TAG, "restock +${pick.size} (cold was $currentColdSize / $max)")
        return pick
    }

    /**
     * Exhaust / auto-play path: plan a new batch; creates artist session if needed.
     */
    fun maybePlan(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): RadioBatch? {
        val radioActive = session?.active == true
        if (!radioActive && !settings.isAutoPlayRecommendedEnabled()) {
            Log.d(TAG, "skip — no session & auto-play off")
            return null
        }
        if (repeatMode != RepeatMode.OFF && repeatMode != RepeatMode.ONE) {
            // ONE is track-local; still allow restock after one ends via advance
            if (repeatMode != RepeatMode.OFF) {
                Log.d(TAG, "skip plan — repeatMode=$repeatMode")
                return null
            }
        }
        if (repeatMode != RepeatMode.OFF) return null

        if (session?.active != true) {
            ensureAutoPlaySession(seedSong, finishedSource)
        }
        return planBatch(seedSong, finishedSource)
    }

    // ── planning helpers ──────────────────────────────────────────────────

    private fun planShuffledSongs(
        releases: List<AlbumItem>,
        max: Int,
        excludeAlbumKeys: Set<String>
    ): List<Song> {
        val pool = songPool(
            releases.filter { ReleaseClassifier.releaseKey(it) !in excludeAlbumKeys }
        )
        if (pool.isEmpty()) return emptyList()
        return pool.shuffled(Random(System.nanoTime())).take(max)
    }

    private fun planReleaseBlocks(
        releases: List<AlbumItem>,
        max: Int,
        excludeAlbumKeys: Set<String>
    ): List<Song> {
        val ordered = releases
            .filter { ReleaseClassifier.releaseKey(it) !in excludeAlbumKeys }
            .sortedWith(
                compareByDescending<AlbumItem> {
                    it.songs.mapNotNull { s -> s.year }.maxOrNull() ?: Int.MIN_VALUE
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            )
        val out = ArrayList<Song>(max + 32)
        for (rel in ordered) {
            val tracks = rel.songs.sortedWith(
                compareBy<Song> { it.discNumber ?: 1 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
            )
            if (tracks.isEmpty()) continue
            out.addAll(tracks)
            memory.note(ReleaseClassifier.releaseKey(rel), ReleaseClassifier.kindOf(rel))
            if (out.size >= max) break
        }
        return out
    }

    private fun availableReleases(excludeKeys: Set<String>): List<AlbumItem> {
        val base = if (poolReleases.isNotEmpty()) poolReleases else catalog.albums()
        return base.filter { it.songs.isNotEmpty() && ReleaseClassifier.releaseKey(it) !in excludeKeys }
    }

    private fun songPool(releases: List<AlbumItem>): List<Song> =
        releases.flatMap { it.songs }.distinctBy { songKey(it) }

    private fun releasesForArtist(artistName: String): List<AlbumItem> {
        val norm = artistKey(artistName) ?: return emptyList()
        return catalog.albums().filter { album ->
            artistKey(album.artist) == norm ||
                album.songs.any { s ->
                    artistKey(s.effectiveAlbumArtist) == norm ||
                        artistKey(s.artist) == norm ||
                        s.creditArtists.any { artistKey(it) == norm }
                }
        }
    }

    private fun rebuildPoolFromConfig(config: ReleasePoolConfig) {
        val keys = config.artistKeys.mapNotNull { artistKey(it) }.toSet()
        poolReleases = if (keys.isEmpty()) {
            catalog.albums().filter { it.songs.isNotEmpty() }
        } else {
            catalog.albums().filter { album ->
                album.songs.isNotEmpty() && (
                    artistKey(album.artist)?.let { it in keys } == true ||
                        album.songs.any { s ->
                            artistKey(s.effectiveAlbumArtist)?.let { it in keys } == true ||
                                artistKey(s.artist)?.let { it in keys } == true ||
                                s.creditArtists.any { artistKey(it)?.let { k -> k in keys } == true }
                        }
                    )
            }
        }
    }

    private fun albumKeyOf(s: Song): String {
        val a = (s.effectiveAlbumArtist ?: "").trim().lowercase()
        val n = (s.album ?: "").trim().lowercase()
        return "$a|$n"
    }

    private fun songKey(s: Song): String =
        s.path?.lowercase() ?: s.contentUri.toString()

    companion object {
        private const val TAG = "YuriPlayer.Radio"
    }
}
