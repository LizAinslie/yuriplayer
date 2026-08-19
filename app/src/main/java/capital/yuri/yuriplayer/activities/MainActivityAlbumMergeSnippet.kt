package capital.yuri.yuriplayer.activities

/**
 * Temporary marker. The real wiring belongs in MainActivity:
 *
 * openAlbumForSong / DetailRoute.Album LaunchedEffect should be:
 *
 * ```
 * val fromCatalog = catalog.albumItemForKey(key)
 * val fromLocal = findLocalAlbum(library, name, artist)
 * liveAlbum = mergeAlbumSources(seed, fromCatalog, fromLocal)
 * ```
 *
 * Implemented next by editing MainActivity directly.
 */
