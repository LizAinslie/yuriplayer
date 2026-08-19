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

/**
 * Find local device tracks for this release.
 *
 * Tries structured [LibraryIndex.albums] first, then a raw scan of
 * [LibraryIndex.songs] so we still match when album-artist tags differ
 * slightly (e.g. Øne vs One) or albums() grouping keyed only on title.
 */
fun findLocalAlbum(
    library: LibraryIndex,
    name: String?,
    artist: String?
): AlbumItem? {
    if (name.isNullOrBlank()) return null
    val key = albumKey(name, artist)

    library.albums(taggedOnly = false).firstOrNull {
        albumKey(it.name, it.artist) == key
    }?.let { return it }

    library.albums(taggedOnly = false).firstOrNull {
        it.name.equals(name, ignoreCase = true) &&
            (artist.isNullOrBlank() || it.artist.equals(artist, ignoreCase = true))
    }?.let { return it }

    // Raw song scan — catches local files whose albumArtist differs from JF
    val nameFolded = TrackIdentity.normalizeToken(name)
    val artistFolded = TrackIdentity.normalizeToken(artist)
    val matches = library.songs.value.filter { song ->
        TrackIdentity.albumsMatch(song.album, name) ||
            TrackIdentity.normalizeToken(song.album) == nameFolded
    }.filter { song ->
        if (artistFolded.isEmpty()) return@filter true
        val aa = TrackIdentity.normalizeToken(song.effectiveAlbumArtist)
        aa.isEmpty() || aa == artistFolded
    }
    if (matches.isEmpty()) {
        // Last resort: album title only (still better than a 1-track JF seed)
        val byTitle = library.songs.value.filter {
            TrackIdentity.albumsMatch(it.album, name)
        }
        if (byTitle.isEmpty()) return null
        val deduped = CatalogRepository.dedupeLogicalTracks(byTitle)
        return AlbumItem(
            name = name,
            artist = artist ?: deduped.firstOrNull()?.effectiveAlbumArtist,
            trackCount = deduped.size,
            songs = deduped
        )
    }
    val deduped = CatalogRepository.dedupeLogicalTracks(matches)
    return AlbumItem(
        name = name,
        artist = artist ?: deduped.firstOrNull()?.effectiveAlbumArtist,
        trackCount = deduped.size,
        songs = deduped
    )
}
