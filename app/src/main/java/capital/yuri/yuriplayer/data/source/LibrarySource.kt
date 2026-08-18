package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.ArtistItem
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Plugin-friendly SPI for a music library backend (local MediaStore, Jellyfin,
 * Navidrome, WebDAV, future JAR plugins on desktop).
 *
 * Call sites should depend on [LibrarySourceRegistry] / aggregators, not a
 * concrete scanner. Local remains the default implementation owned by LibraryIndex.
 */
interface LibrarySource {
    /** Stable id e.g. "local", "jellyfin:<serverId>". */
    val id: String

    val displayName: String

    val type: SourceType

    /** Whether this source is currently reachable / enabled. */
    suspend fun isAvailable(): Boolean = true

    /** Full rescan (or incremental if the source supports it). */
    suspend fun scan(): LibrarySnapshot

    /** Optional live updates; default is a single emission of [scan]. */
    fun observe(): Flow<LibrarySnapshot> = flowOf()
}

data class LibrarySnapshot(
    val sourceId: String,
    val songs: List<Song> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val scannedAtMs: Long = System.currentTimeMillis()
)

/**
 * Holds every [LibrarySource] registered in Koin. Desktop JAR plugins will add
 * more instances the same way built-in local/remote sources do.
 */
class LibrarySourceRegistry(
    private val sources: List<LibrarySource>
) {
    fun all(): List<LibrarySource> = sources

    fun byId(id: String): LibrarySource? = sources.firstOrNull { it.id == id }

    suspend fun available(): List<LibrarySource> =
        sources.filter { runCatching { it.isAvailable() }.getOrDefault(false) }
}
