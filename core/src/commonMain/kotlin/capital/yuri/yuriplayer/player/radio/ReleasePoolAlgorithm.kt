package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.ColdSourceType
import kotlin.random.Random

/**
 * Draws random releases from a user-defined artist (and later genre) pool.
 * Used for playlist-derived radios and explicit “station” configs.
 *
 * Network: [ExternalReleaseSource] is consulted only when
 * [ReleasePoolConfig.allowExternalFetch] is true **and** the source is enabled.
 * Default is local-only — privacy gate stays closed until the user opts in.
 */
class ReleasePoolAlgorithm(
    var config: ReleasePoolConfig = ReleasePoolConfig(),
    private val external: ExternalReleaseSource = NoExternalReleaseSource
) : RadioAlgorithm {

    override val id: RadioAlgorithmId = RadioAlgorithmId.RELEASE_POOL

    override fun pick(
        catalog: ReleaseCatalog,
        context: RadioContext,
        memory: RecentReleaseMemory
    ): RadioPick? {
        val artistFocus = (config.artistKeys.mapNotNull { artistKey(it) } +
            context.focusArtistKeys).toSet()

        if (artistFocus.isEmpty() && config.genreKeys.isEmpty()) {
            log.i { "empty pool" }
            return null
        }

        var pool = catalog.albums().filter { it.songs.isNotEmpty() }

        if (artistFocus.isNotEmpty()) {
            pool = pool.filter { albumMatchesArtists(it, artistFocus) }
        }
        if (config.genreKeys.isNotEmpty()) {
            val genreNorm = config.genreKeys.map { it.lowercase() }.toSet()
            pool = pool.filter { albumMatchesGenres(it, genreNorm) }
        }

        pool = pool.filter { album ->
            when (ReleaseClassifier.kindOf(album)) {
                ReleaseKind.LP -> config.includeLps
                ReleaseKind.EP -> config.includeEps
                ReleaseKind.SINGLE -> config.includeSingles
                ReleaseKind.UNKNOWN -> true
            }
        }

        if (config.allowExternalFetch && external.isEnabled) {
            // Stub: merge external candidates when privacy gate opens.
            val remote = external.fetchCandidates(context)
            if (remote.isNotEmpty()) {
                log.i { "external returned ${remote.size} (not persisted)" }
                pool = pool + remote.filter { it.songs.isNotEmpty() }
            }
        }

        if (pool.isEmpty()) {
            log.i { "no local releases in pool artists=$artistFocus" }
            return null
        }

        val filtered = pool.filter { album ->
            val key = ReleaseClassifier.releaseKey(album)
            val kind = ReleaseClassifier.kindOf(album)
            val hard = memory.hardExclude(
                kind = kind,
                perKind = config.avoidRecentPerKind,
                globalCount = config.avoidRecentPerKind
            )
            key !in hard && hard.none { it.equals(key, true) }
        }.ifEmpty { pool }

        val album = filtered[Random.nextInt(filtered.size)]
        val key = ReleaseClassifier.releaseKey(album)
        val kind = ReleaseClassifier.kindOf(album)
        log.i { "pool pick '${album.displayName}' kind=$kind from ${filtered.size}" }
        return RadioPick(
            album = album,
            source = ColdSource(ColdSourceType.ALBUM, key, album.name),
            kind = kind,
            algorithmId = id.name,
            reason = "pool artists=${artistFocus.size}"
        )
    }

    private fun albumMatchesArtists(album: AlbumItem, focus: Set<String>): Boolean {
        artistKey(album.artist)?.let { if (it in focus) return true }
        return album.songs.any { song ->
            artistKey(song.effectiveAlbumArtist)?.let { it in focus } == true ||
                artistKey(song.artist)?.let { it in focus } == true ||
                song.creditArtists.any { artistKey(it)?.let { k -> k in focus } == true }
        }
    }

    private fun albumMatchesGenres(album: AlbumItem, genreNorm: Set<String>): Boolean =
        album.songs.any { song ->
            song.genres.any { it.lowercase() in genreNorm }
        }

    companion object {
        private val log = yuriLog("RadioPool")
    }
}
