package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.CatalogRepository
import capital.yuri.yuriplayer.data.LibraryIndex

/**
 * Built-in local files source. Scan mode (MediaStore vs manual SAF) lives in
 * [capital.yuri.yuriplayer.data.LibrarySettings] and is honored by MusicRepository.
 */
class LocalLibrarySource(
    private val catalog: CatalogRepository,
    private val libraryIndex: LibraryIndex
) : LibrarySource {
    override val id: String = "local"
    override val displayName: String = "Local files"
    override val type: SourceType = SourceType.LOCAL

    override suspend fun isAvailable(): Boolean = true

    override suspend fun scan(): LibrarySnapshot {
        val songs = catalog.syncLocalLibrary()
        // Keep LibraryIndex in sync when something else triggers a source scan.
        // LibraryIndex.refresh() is the primary path from UI.
        return LibrarySnapshot(
            sourceId = id,
            songs = songs,
            scannedAtMs = System.currentTimeMillis()
        )
    }
}
