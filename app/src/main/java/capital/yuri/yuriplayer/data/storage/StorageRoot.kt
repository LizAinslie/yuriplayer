package capital.yuri.yuriplayer.data.storage

import java.io.InputStream
import java.io.OutputStream

/**
 * Common read/write surface for **folder-like** backends.
 *
 * This is intentionally **not** Okio [okio.FileSystem]:
 * - Operations are suspend (network / SAF can block or need Dispatchers.IO)
 * - Identity is often a URI or remote id, not a pure POSIX path
 * - Auth, rate limits, and partial failure are first-class
 *
 * Catalog sources (Jellyfin / Subsonic) stay on [capital.yuri.yuriplayer.data.source.LibrarySource].
 * StorageRoot is for trees you can list/move/rename (local SAF, WebDAV, Drive, Nextcloud, …).
 */
interface StorageRoot {
    /** Stable id, e.g. saf tree URI or "webdav:<instanceId>". */
    val id: String

    val displayName: String

    val capabilities: StorageCapabilities

    /** Relative path uses `/` separators; empty string = root. */
    suspend fun list(path: String = ""): List<StorageEntry>

    suspend fun metadata(path: String): StorageEntry?

    suspend fun openRead(path: String): InputStream

    /**
     * Open for write. [overwrite] replaces an existing document when true.
     * Parent directories are created when [capabilities.canMkdir] is true.
     */
    suspend fun openWrite(path: String, overwrite: Boolean = true): OutputStream

    suspend fun mkdir(path: String): Boolean

    suspend fun delete(path: String, recursive: Boolean = false): Boolean

    /**
     * Move or rename within this root. Cross-root moves are the caller's job
     * (copy + delete) when both roots support it.
     */
    suspend fun move(fromPath: String, toPath: String, overwrite: Boolean = false): Boolean

    suspend fun exists(path: String): Boolean = metadata(path) != null
}

data class StorageCapabilities(
    val canRead: Boolean = true,
    val canWrite: Boolean = false,
    val canMkdir: Boolean = false,
    val canDelete: Boolean = false,
    val canMove: Boolean = false,
    /** True when paths are stable across sessions (SAF document ids, not temp mounts). */
    val persistent: Boolean = true
)

data class StorageEntry(
    /** Path relative to the root, `/`-separated. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val lastModifiedMs: Long? = null,
    /** Backend-specific handle (content URI, remote id, …). */
    val nativeId: String? = null
)

/**
 * Registry of mounted [StorageRoot]s (SAF trees today; cloud mounts later).
 */
class StorageRootRegistry(
    private val roots: List<StorageRoot>
) {
    fun all(): List<StorageRoot> = roots

    fun byId(id: String): StorageRoot? = roots.firstOrNull { it.id == id }

    fun writable(): List<StorageRoot> =
        roots.filter { it.capabilities.canWrite }
}
