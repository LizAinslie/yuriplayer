package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey

/**
 * Album radio pool: every credited artist on the release, plus genre keys
 * when those tags exist (Song has no genre field yet — empty list is fine).
 */
object AlbumRadioSeed {

    fun fromAlbum(album: AlbumItem): Pair<ReleasePoolConfig, RadioSession> {
        val artists = LinkedHashSet<String>()
        val displayArtists = LinkedHashSet<String>()

        album.artist?.trim()?.takeIf { it.isNotEmpty() }?.let {
            displayArtists += it
            artistKey(it)?.let(artists::add)
        }

        for (s in album.songs) {
            collectArtist(s, artists, displayArtists)
        }

        val genres = collectGenres(album.songs)
        val cfg = ReleasePoolConfig(
            artistKeys = artists.toList(),
            genreKeys = genres,
            includeLps = true,
            includeEps = true,
            includeSingles = true,
            allowExternalFetch = false,
            avoidRecentPerKind = 1
        )

        val seedKey = ReleaseClassifier.releaseKey(album)
        val label = album.name?.takeIf { it.isNotBlank() } ?: "Album"
        val session = RadioSession(
            kind = RadioSessionKind.ALBUM,
            displayName = "Radio · $label",
            algorithmId = RadioAlgorithmId.RELEASE_POOL,
            seedId = seedKey,
            seedTitle = label
        )
        return cfg to session
    }

    fun fromTracksForPlaylist(
        songs: List<Song>,
        playlistName: String?
    ): Pair<ReleasePoolConfig, RadioSession> {
        val cfg = PlaylistRadioSeed.fromTracks(songs)
        val label = playlistName?.takeIf { it.isNotBlank() } ?: "Playlist"
        val session = RadioSession(
            kind = RadioSessionKind.PLAYLIST,
            displayName = "Radio · $label",
            algorithmId = RadioAlgorithmId.RELEASE_POOL,
            seedId = label.lowercase(),
            seedTitle = label
        )
        return cfg to session
    }

    fun artistSession(artistName: String): RadioSession {
        val label = artistName.trim().ifBlank { "Artist" }
        return RadioSession(
            kind = RadioSessionKind.ARTIST,
            displayName = "Radio · $label",
            algorithmId = RadioAlgorithmId.PLAYBACK,
            seedId = artistKey(label),
            seedTitle = label
        )
    }

    private fun collectArtist(
        s: Song,
        keys: MutableSet<String>,
        display: MutableSet<String>
    ) {
        s.effectiveAlbumArtist?.trim()?.takeIf { it.isNotEmpty() }?.let {
            display += it
            artistKey(it)?.let(keys::add)
        }
        s.artist?.trim()?.takeIf { it.isNotEmpty() }?.let {
            display += it
            artistKey(it)?.let(keys::add)
        }
        s.creditArtists.forEach { name ->
            display += name
            artistKey(name)?.let(keys::add)
        }
    }

    /** Collect the distinct genre tags across the seeded release. */
    private fun collectGenres(songs: List<Song>): List<String> =
        songs.flatMap { it.genres }
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
