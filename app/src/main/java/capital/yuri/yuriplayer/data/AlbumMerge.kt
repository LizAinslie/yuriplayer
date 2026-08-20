package capital.yuri.yuriplayer.data

/**
 * Prefer the full track list for a release by unioning every known copy
 * (catalog expand + local LibraryIndex + navigation seed) then deduping
 * with [CatalogRepository.dedupeLogicalTracks] (local preferred for playback).
 *
 * Multi-source = one logical track with offerings, NOT duplicate album cards.
 * Never replaces a rich list with a thinner one (Clancy flash bug).
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

    // Guard: if seed already had a full album and merge somehow lost tracks, keep union
    val songs = if (seed.songs.size > 1 && merged.size < seed.songs.size) {
        CatalogRepository.dedupeLogicalTracks(seed.songs + merged)
    } else {
        merged
    }

    return AlbumItem(
        name = fromCatalog?.name
            ?: fromLocal?.name
            ?: seed.name
            ?: songs.firstOrNull()?.album,
        artist = fromCatalog?.artist
            ?: fromLocal?.artist
            ?: seed.artist
            ?: songs.firstOrNull()?.effectiveAlbumArtist,
        trackCount = songs.size.coerceAtLeast(
            listOfNotNull(fromCatalog?.trackCount, fromLocal?.trackCount, seed.trackCount).maxOrNull() ?: 0
        ),
        songs = songs
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
        TrackIdentity.albumsMatch(it.name, name) &&
            (artist.isNullOrBlank() || TrackIdentity.albumArtistsMatch(it.artist, artist))
    }?.let { return it }

    val nameFolded = TrackIdentity.normalizeToken(name)
    val artistFolded = TrackIdentity.normalizeToken(artist)
    val matches = library.songs.value.filter { song ->
        TrackIdentity.albumsMatch(song.album, name) ||
            TrackIdentity.normalizeToken(song.album) == nameFolded
    }.filter { song ->
        if (artistFolded.isEmpty()) return@filter true
        val aa = TrackIdentity.normalizeToken(song.effectiveAlbumArtist)
        if (aa.isEmpty()) return@filter true
        aa == artistFolded || aa.contains(artistFolded) || artistFolded.contains(aa)
    }
    if (matches.isEmpty()) return null
    val deduped = CatalogRepository.dedupeLogicalTracks(matches)
    return AlbumItem(
        name = name,
        artist = artist ?: deduped.firstOrNull()?.effectiveAlbumArtist,
        trackCount = deduped.size,
        songs = deduped
    )
}
