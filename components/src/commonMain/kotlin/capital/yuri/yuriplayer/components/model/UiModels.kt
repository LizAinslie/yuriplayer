package capital.yuri.yuriplayer.components.model

import capital.yuri.yuriplayer.core.library.Track

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
