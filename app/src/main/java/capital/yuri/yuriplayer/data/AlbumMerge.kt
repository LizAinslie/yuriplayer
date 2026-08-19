package capital.yuri.yuriplayer.data

/**
 * Prefer the full track list for a release by unioning every known copy
 * (catalog expand + local LibraryIndex + navigation seed) then deduping
 * with [CatalogRepository.dedupeLogicalTracks] (local preferred for playback).
 */
fun mergeAlbumSources(
    seed: AlbumItem,
    fromCatalog: AlbumItem?,
    fromLocal: AlbumItem?
): AlbumItem {
    val merged = CatalogRepository.dedupeLogicalTracks(
        buildList {
            addAll(fromCatalog?.songs.orEmpty())
            addAll(fromLocal?.songs.orEmpty())
            addAll(seed.songs)
        }
    )
    if (merged.isEmpty()) return seed
    return AlbumItem(
        name = fromCatalog?.name
            ?: fromLocal?.name
            ?: seed.name
            ?: merged.firstOrNull()?.album,
        artist = fromCatalog?.artist
            ?: fromLocal?.artist
            ?: seed.artist
            ?: merged.firstOrNull()?.effectiveAlbumArtist,
        trackCount = merged.size,
        songs = merged
    )
}

fun findLocalAlbum(
    library: LibraryIndex,
    name: String?,
    artist: String?
): AlbumItem? {
    if (name.isNullOrBlank()) return null
    val key = albumKey(name, artist)
    return library.albums(taggedOnly = false).firstOrNull {
        albumKey(it.name, it.artist) == key
    } ?: library.albums(taggedOnly = false).firstOrNull {
        it.name.equals(name, ignoreCase = true) &&
            (artist.isNullOrBlank() || it.artist.equals(artist, ignoreCase = true))
    } ?: library.albums(taggedOnly = false).firstOrNull {
        // Last resort: album title only (handles Ø vs O artist tag mismatch pre-fold)
        it.name.equals(name, ignoreCase = true)
    }
}
