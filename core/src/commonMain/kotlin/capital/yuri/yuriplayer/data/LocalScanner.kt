package capital.yuri.yuriplayer.data

/**
 * Platform seam for scanning the local device library into [Song] rows.
 *
 * - Android wires [MusicRepository.scanLibrary] (MediaStore / SAF).
 * - JVM/desktop wires [LocalLibraryScanner.scanSongs] (folder walk).
 *
 * Kept as a single-method functional interface so [CatalogRepository] lives in
 * commonMain without importing Context / MediaStore / File.
 */
fun interface LocalScanner {
    suspend fun scan(): List<Song>
}
