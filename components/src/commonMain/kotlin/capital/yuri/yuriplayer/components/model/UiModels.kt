package capital.yuri.yuriplayer.components.model

import capital.yuri.yuriplayer.core.library.albumGroupKey
import capital.yuri.yuriplayer.core.library.catalogKey
import capital.yuri.yuriplayer.core.library.collapseAlbumTracks
import capital.yuri.yuriplayer.core.library.matchesSearch
import capital.yuri.yuriplayer.data.Song

data class CoverRef(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?
)

data class TrackRowModel(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val artworkUri: String? = null,
    val highlighted: Boolean = false,
    val explicit: Boolean = false,
    val multiSource: Boolean = false,
    val sourceIds: List<String> = emptyList()
)

data class AlbumPageModel(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val year: Int? = null,
    val tracks: List<TrackRowModel>
)

data class ArtistPageModel(
    val name: String,
    val artworkUri: String?,
    val bannerUri: String? = null,
    val stats: String,
    val about: String? = null,
    val popular: List<TrackRowModel>,
    val liked: List<TrackRowModel>,
    val likedCount: Int,
    val likedReleaseCount: Int,
    val discography: List<AlbumPageModel>,
    val appearsOn: List<AlbumPageModel>,
    val genres: List<String> = emptyList()
)

fun Song.toRow(highlighted: Boolean = false) = TrackRowModel(
    id = songKey,
    title = displayTitle,
    artist = displayArtist,
    album = displayAlbum,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    artworkUri = albumArtUri,
    highlighted = highlighted,
    explicit = isExplicit,
    multiSource = false,
    sourceIds = listOf(songKey)
)

fun Song.toCover() = CoverRef(
    id = songKey,
    title = displayTitle,
    subtitle = displayArtist,
    artworkUri = albumArtUri
)

fun List<Song>.albums(
    preferredIds: Map<String, String> = emptyMap()
): List<AlbumPageModel> =
    groupBy { albumGroupKey(it) }
        .mapNotNull { (_, songs) ->
            val collapsed = collapseAlbumTracks(songs, preferredIds)
            val first = collapsed.firstOrNull()?.preferred ?: return@mapNotNull null
            AlbumPageModel(
                id = albumGroupKey(first),
                title = first.displayAlbum,
                artist = first.albumArtist?.takeIf { it.isNotBlank() } ?: first.displayArtist,
                artworkUri = collapsed.firstNotNullOfOrNull { it.preferred.albumArtUri },
                year = collapsed.mapNotNull { it.preferred.year }.maxOrNull(),
                tracks = collapsed.map { it.toRow() }
            )
        }
        .sortedBy { it.title.lowercase() }

private fun capital.yuri.yuriplayer.core.library.CollapsedSong.toRow() = TrackRowModel(
    id = preferred.songKey,
    title = preferred.displayTitle,
    artist = preferred.displayArtist,
    album = preferred.displayAlbum,
    durationMs = preferred.durationMs,
    trackNumber = preferred.trackNumber,
    discNumber = preferred.discNumber,
    artworkUri = preferred.albumArtUri,
    explicit = explicit,
    multiSource = multiSource,
    sourceIds = sources.map { it.songKey }
)

fun List<Song>.artistPage(
    name: String,
    likedIds: Set<String> = emptySet(),
    recents: List<Song> = emptyList()
): ArtistPageModel {
    val allAlbums = albums()
    val discography = allAlbums.filter { it.artist.matchesSearch(name) }
        .sortedByDescending { it.year ?: Int.MIN_VALUE }
    val appearsOn = allAlbums.filter { album ->
        !album.artist.matchesSearch(name) &&
            album.tracks.any { row ->
                row.artist.matchesSearch(name)
            }
    }.sortedByDescending { it.year ?: Int.MIN_VALUE }

    // One logical track per recording: local + Jellyfin + Subsonic copies of the
    // same song collapse onto a single entry, so track counts / popular / liked
    // don't inflate for multi-source discographies (e.g. Lemon Demon).
    val ofArtist = filter {
        it.displayArtist.matchesSearch(name) || (it.albumArtist?.matchesSearch(name) == true)
    }.distinctBy { it.catalogKey() }

    val recentRank = recents.mapIndexed { i, t -> t.songKey to i }.toMap()
    val popular = ofArtist
        .sortedWith(
            compareBy<Song> { recentRank[it.songKey] ?: Int.MAX_VALUE }
                .thenBy { it.displayAlbum }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        )
        .distinctBy { it.displayTitle.lowercase() }
        .take(5)
        .map { it.toRow() }
    val likedTracks = ofArtist.filter { it.songKey in likedIds }
    return ArtistPageModel(
        name = name,
        artworkUri = ofArtist.firstNotNullOfOrNull { it.albumArtUri } ?: discography.firstOrNull()?.artworkUri,
        bannerUri = null,
        stats = "${discography.size} albums · ${ofArtist.size} tracks",
        about = null,
        popular = popular,
        liked = likedTracks.take(8).map { it.toRow() },
        likedCount = likedTracks.size,
        likedReleaseCount = likedTracks.map { it.displayAlbum }.distinct().size,
        discography = discography,
        appearsOn = appearsOn
    )
}
