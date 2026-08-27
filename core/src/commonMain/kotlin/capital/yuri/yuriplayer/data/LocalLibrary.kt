package capital.yuri.yuriplayer.data

/**
 * Minimal, platform-agnostic view of the in-memory **local** library.
 *
 * Android wires [LibraryIndex]; a JVM host can back this with [SongLibrary]
 * over its scanned songs. Kept behind an interface so album resolution stays in
 * commonMain without importing Context / MediaStore.
 */
interface LocalLibrary {
    /** Grouped album items (optionally only tagged releases). */
    fun albums(taggedOnly: Boolean = true): List<AlbumItem>

    /** Current flat local song list. */
    fun songs(): List<Song>
}
