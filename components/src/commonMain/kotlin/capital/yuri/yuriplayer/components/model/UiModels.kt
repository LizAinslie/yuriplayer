package capital.yuri.yuriplayer.components.model

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.library.matchesSearch

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
    val highlighted: Boolean = false
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
    val appearsOn: List<AlbumPageModel>
)

fun Track.toRow(highlighted: Boolean = false) = TrackRowModel(
    id = id,
    title = displayTitle,
    artist = displayArtist,
    album = displayAlbum,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    artworkUri = artworkUri,
    highlighted = highlighted
)

fun Track.toCover() = CoverRef(
    id = id,
    title = displayTitle,
    subtitle = displayArtist,
    artworkUri = artworkUri
)

fun List<Track>.albums(): List<AlbumPageModel> =
    groupBy { (it.album ?: "").trim().ifBlank { "Unknown Album" } to (it.albumArtist ?: it.artist ?: "") }
        .map { (key, tracks) ->
            val sorted = tracks.sortedWith(
                compareBy<Track> { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }
            )
            AlbumPageModel(
                id = "${key.second}::${key.first}",
                title = key.first,
                artist = key.second.ifBlank { sorted.first().displayArtist },
                artworkUri = sorted.firstNotNullOfOrNull { it.artworkUri },
                year = sorted.mapNotNull { it.year }.maxOrNull(),
                tracks = sorted.map { it.toRow() }
            )
        }
        .sortedBy { it.title.lowercase() }

fun List<Track>.artistPage(
    name: String,
    likedIds: Set<String> = emptySet(),
    recents: List<Track> = emptyList()
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
    val ofArtist = filter { it.displayArtist.matchesSearch(name) || (it.albumArtist?.matchesSearch(name) == true) }
    val recentRank = recents.mapIndexed { i, t -> t.id to i }.toMap()
    val popular = ofArtist
        .sortedWith(
            compareBy<Track> { recentRank[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.displayAlbum }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        )
        .distinctBy { it.displayTitle.lowercase() }
        .take(5)
        .map { it.toRow() }
    val likedTracks = ofArtist.filter { it.id in likedIds }
    return ArtistPageModel(
        name = name,
        artworkUri = ofArtist.firstNotNullOfOrNull { it.artworkUri } ?: discography.firstOrNull()?.artworkUri,
        bannerUri = discography.firstOrNull()?.artworkUri ?: ofArtist.firstNotNullOfOrNull { it.artworkUri },
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
