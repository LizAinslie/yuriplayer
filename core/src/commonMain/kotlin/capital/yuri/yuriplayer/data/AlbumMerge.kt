package capital.yuri.yuriplayer.data

/**
 * Union every known copy of a release. Multi-source = one logical track with
 * offerings, NOT duplicate album cards. Never replaces a rich list with a thinner one.
 */
fun mergeAlbumSources(
    seed: AlbumItem,
    fromCatalog: AlbumItem?,
    fromLocal: AlbumItem?
): AlbumItem {
    val album = seed.name ?: fromCatalog?.name ?: fromLocal?.name
    AlbumLog.i(album, "merge seed=${seed.songs.size} catalog=${fromCatalog?.songs?.size ?: 0} local=${fromLocal?.songs?.size ?: 0} " +
            "seedName='${seed.name}' catName='${fromCatalog?.name}' localName='${fromLocal?.name}'")
    AlbumLog.songs(album, "merge.seed", seed.songs)
    AlbumLog.songs(album, "merge.catalog", fromCatalog?.songs.orEmpty())
    AlbumLog.songs(album, "merge.local", fromLocal?.songs.orEmpty())

    val merged = dedupeAlbumPageTracks(
        buildList {
            addAll(fromCatalog?.songs.orEmpty())
            addAll(fromLocal?.songs.orEmpty())
            addAll(seed.songs)
        }
    )
    if (merged.isEmpty()) {
        AlbumLog.w(album, "merge empty → keep seed")
        return seed
    }

    // Absolute never-shrink against every input
    val richest = listOfNotNull(
        fromCatalog?.songs?.size,
        fromLocal?.songs?.size,
        seed.songs.size,
        merged.size
    ).maxOrNull() ?: merged.size

    val songs = if (merged.size < richest && seed.songs.size >= richest) {
        dedupeAlbumPageTracks(seed.songs + merged)
    } else if (merged.size < richest && (fromCatalog?.songs?.size ?: 0) >= richest) {
        dedupeAlbumPageTracks(fromCatalog!!.songs + merged)
    } else if (merged.size < richest && (fromLocal?.songs?.size ?: 0) >= richest) {
        dedupeAlbumPageTracks(fromLocal!!.songs + merged)
    } else {
        merged
    }

    if (songs.size < richest) {
        AlbumLog.w(album, "merge SHRANK richest=$richest → ${songs.size}")
        AlbumLog.songs(album, "merge.out", songs)
    } else {
        AlbumLog.i(album, "merge out n=${songs.size} richest=$richest")
    }

    return AlbumItem(
        name = seed.name
            ?: fromLocal?.name
            ?: fromCatalog?.name
            ?: songs.firstOrNull()?.album,
        artist = fromCatalog?.artist
            ?: fromLocal?.artist
            ?: seed.artist
            ?: songs.firstOrNull()?.effectiveAlbumArtist,
        trackCount = songs.size.coerceAtLeast(
            listOfNotNull(
                fromCatalog?.trackCount,
                fromLocal?.trackCount,
                seed.trackCount,
                songs.size
            ).maxOrNull() ?: 0
        ),
        songs = songs
    )
}

fun findLocalAlbum(
    library: LocalLibrary,
    name: String?,
    artist: String?
): AlbumItem? {
    if (name.isNullOrBlank()) return null
    val key = albumKey(name, artist)

    library.albums(taggedOnly = false).firstOrNull {
        albumKey(it.name, it.artist) == key
    }?.let {
        AlbumLog.i(name, "findLocalAlbum exact key='$key' n=${it.songs.size}")
        return it
    }

    library.albums(taggedOnly = false).firstOrNull {
        TrackIdentity.albumsMatch(it.name, name) &&
            (artist.isNullOrBlank() || TrackIdentity.albumArtistsMatch(it.artist, artist))
    }?.let {
        AlbumLog.i(name, "findLocalAlbum fuzzy n=${it.songs.size} artist='${it.artist}'")
        return it
    }

    library.albums(taggedOnly = false).firstOrNull {
        TrackIdentity.albumsNearlyMatch(it.name, name) &&
            (artist.isNullOrBlank() || TrackIdentity.albumArtistsMatch(it.artist, artist))
    }?.let {
        AlbumLog.i(name, "findLocalAlbum typo n=${it.songs.size} local='${it.name}'")
        return it
    }

    val nameFolded = TrackIdentity.normalizeToken(name)
    val matches = library.songs().filter { song ->
        TrackIdentity.albumsMatch(song.album, name) ||
            TrackIdentity.normalizeToken(song.album) == nameFolded
    }.filter { song ->
        if (artist.isNullOrBlank()) return@filter true
        val aa = song.effectiveAlbumArtist
        if (aa.isNullOrBlank()) return@filter true
        TrackIdentity.albumArtistsMatch(aa, artist)
    }
    if (matches.isEmpty()) return null
    AlbumLog.i(name, "findLocalAlbum matches=${matches.size} key='$key'")
    AlbumLog.songs(name, "findLocal", matches)
    val deduped = dedupeAlbumPageTracks(matches)
    return AlbumItem(
        name = name,
        artist = artist ?: deduped.firstOrNull()?.effectiveAlbumArtist,
        trackCount = deduped.size,
        songs = deduped
    )
}
