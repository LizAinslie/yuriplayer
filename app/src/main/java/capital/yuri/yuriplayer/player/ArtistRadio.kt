package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import kotlin.random.Random

/**
 * Simple same-artist "radio": when a cold queue ends with Repeat off and
 * Auto-play recommended is enabled, pick another album/single from that artist.
 *
 * Excludes the album that just finished and the one played immediately before it.
 * Genre-based recommendations come later.
 */
object ArtistRadio {

    private const val TAG = "YuriPlayer.Radio"

    data class Pick(
        val album: AlbumItem,
        val source: ColdSource
    )

    fun pickNextAlbum(
        library: LibraryIndex,
        seedSong: Song?,
        finishedSource: ColdSource?,
        excludeAlbumKeys: Set<String>
    ): Pick? {
        val artistName = resolveArtistName(seedSong, finishedSource) ?: return null
        val artistNorm = LibraryIndex.normalizeKey(artistName) ?: return null

        val candidates = library.albums(taggedOnly = true).filter { album ->
            val aNorm = LibraryIndex.normalizeKey(album.artist)
            if (aNorm == null || aNorm != artistNorm) return@filter false
            if (album.songs.isEmpty()) return@filter false
            val key = albumKey(album.name, album.artist)
            key !in excludeAlbumKeys
        }

        if (candidates.isEmpty()) {
            Log.i(TAG, "no candidate albums for artist='$artistName' exclude=$excludeAlbumKeys")
            return null
        }

        val pick = candidates[Random.nextInt(candidates.size)]
        val key = albumKey(pick.name, pick.artist)
        Log.i(
            TAG,
            "picked '${pick.displayName}' by ${pick.displayArtist} " +
                "(${pick.trackCount} tracks) from ${candidates.size} candidates"
        )
        return Pick(
            album = pick,
            source = ColdSource(
                type = ColdSourceType.ALBUM,
                id = key,
                title = pick.name
            )
        )
    }

    private fun resolveArtistName(seed: Song?, source: ColdSource?): String? {
        seed?.effectiveAlbumArtist?.takeIf { it.isNotBlank() }?.let { return it }
        seed?.artist?.takeIf { it.isNotBlank() }?.let { return it }
        if (source?.type == ColdSourceType.ARTIST) {
            return source.title?.takeIf { it.isNotBlank() } ?: source.id.takeIf { it.isNotBlank() }
        }
        return null
    }
}
