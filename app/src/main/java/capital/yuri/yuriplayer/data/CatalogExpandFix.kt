package capital.yuri.yuriplayer.data

/**
 * Clancy / multi-source album expand notes
 *
 * Explore search shows multi-source song hits because it searches by *title*.
 * Album pages used [CatalogDao.getTracksForAlbum] which is keyed by albumKey.
 * Local + Jellyfin often store different albumKey strings for the same release
 * (artist tag spelling, albumArtist vs artist), so the page only saw one row.
 *
 * Fix (in CatalogRepository.expandAlbumTracksLocked):
 *  1. dao.getTracksByAlbumName(album) — exact album tag, any source/key
 *  2. Soft artist filter only when result set is huge
 *  3. Still merge folded albumKey variants
 *
 * AlbumDetailScreen also merges catalog + local LibraryIndex seed.
 */
