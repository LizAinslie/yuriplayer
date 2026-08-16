package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.data.artistKey
import kotlin.random.Random

/**
 * Same-artist radio: after a cold queue ends with Repeat off + auto-play on,
 * pick another album/single from that artist (excluding last two album keys).
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
        val artistName = resolveArtistName(seedSong, finishedSource)
        if (artistName == null) {
            Log.i(TAG, "no artist from seed=${seedSong?.displayTitle} source=$finishedSource")
            return null
        }
        val artistNorm = artistKey(artistName) ?: return null

        val candidates = library.albums(taggedOnly = true).filter { album ->
            if (album.songs.isEmpty()) return@filter false
            val key = albumKey(album.name, album.artist)
            if (key in excludeAlbumKeys) return@filter false
            albumMatchesArtist(album, artistNorm)
        }

        if (candidates.isEmpty()) {
            Log.i(
                TAG,
                "no candidates for artist='$artistName' (norm=$artistNorm) " +
                    "exclude=$excludeAlbumKeys libraryAlbums=${library.albums(taggedOnly = true).size}"
            )
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

    private fun albumMatchesArtist(album: AlbumItem, artistNorm: String): Boolean {
        val albumArtist = artistKey(album.artist)
        if (albumArtist != null && albumArtist == artistNorm) return true
        // Any track credit / album-artist on the release
        return album.songs.any { song ->
            artistKey(song.effectiveAlbumArtist) == artistNorm ||
                artistKey(song.artist) == artistNorm ||
                song.creditArtists.any { artistKey(it) == artistNorm }
        }
    }

    private fun resolveArtistName(seed: Song?, source: ColdSource?): String? {
        seed?.effectiveAlbumArtist?.takeIf { it.isNotBlank() }?.let { return it }
        seed?.artist?.takeIf { it.isNotBlank() }?.let { return it }
        seed?.creditArtists?.firstOrNull()?.let { return it }

        when (source?.type) {
            ColdSourceType.ARTIST ->
                return source.title?.takeIf { it.isNotBlank() }
                    ?: source.id.takeIf { it.isNotBlank() }
            ColdSourceType.ALBUM -> {
                // id is "artist|album" from albumKey
                val id = source.id
                if (id.contains('|')) {
                    val left = id.substringBefore('|').trim()
                    if (left.isNotEmpty()) return left
                }
                source.title?.takeIf { it.isNotBlank() }?.let { return null } // title is album name
            }
            else -> Unit
        }
        return null
    }
}
