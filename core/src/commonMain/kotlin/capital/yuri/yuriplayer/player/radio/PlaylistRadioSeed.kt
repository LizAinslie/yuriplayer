package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey

/**
 * Build a [ReleasePoolConfig] from playlist (or any track list) contents.
 * Artists are derived on-device from tags; genres land here when the catalog
 * has them. No network.
 */
object PlaylistRadioSeed {

    fun fromTracks(
        songs: List<Song>,
        includeLps: Boolean = true,
        includeEps: Boolean = true,
        includeSingles: Boolean = true
    ): ReleasePoolConfig {
        val artists = LinkedHashSet<String>()
        for (s in songs) {
            artistKey(s.effectiveAlbumArtist)?.let { artists += it }
            artistKey(s.artist)?.let { artists += it }
            s.creditArtists.forEach { name ->
                artistKey(name)?.let { artists += it }
            }
        }
        return ReleasePoolConfig(
            artistKeys = artists.toList(),
            genreKeys = emptyList(), // filled when Song.genre exists
            includeLps = includeLps,
            includeEps = includeEps,
            includeSingles = includeSingles,
            allowExternalFetch = false,
            avoidRecentPerKind = 1
        )
    }
}
