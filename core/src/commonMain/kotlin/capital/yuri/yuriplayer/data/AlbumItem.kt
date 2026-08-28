package capital.yuri.yuriplayer.data

data class AlbumItem(
    val name: String?,
    val artist: String?,
    val trackCount: Int,
    val songs: List<Song>
) {
    val displayName: String get() = name ?: "Unknown Album"
    val displayArtist: String
        get() = primaryArtistName(artist) ?: artist ?: "Unknown Artist"
}

data class ArtistItem(
    val name: String?,
    val trackCount: Int,
    val albumCount: Int,
    val songs: List<Song>
) {
    val displayName: String get() = primaryArtistName(name) ?: name ?: "Unknown Artist"
}

fun AlbumItem.releaseYear(): Int? = songs.mapNotNull { it.year }.maxOrNull()

fun AlbumItem.releaseType(): ReleaseType = guessReleaseType(trackCount)
