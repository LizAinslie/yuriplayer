package capital.yuri.yuriplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plugin-friendly SPI for a music library origin (local MediaStore, folder,
 * future streaming plugin, etc.).
 *
 * Desktop JAR plugins will register additional [LibrarySource]s the same way
 * built-in local source is registered in Koin today.
 */
interface LibrarySource {
    val id: String
    val displayName: String

    /** Live song list for this source (may be empty while scanning). */
    val songs: StateFlow<List<Song>>

    val isLoading: StateFlow<Boolean>

    /** Kick a rescan / refresh. */
    fun refresh()
}

/**
 * Aggregates multiple [LibrarySource]s into one catalog view.
 * Today: single local source. Later: union / priority of plugins.
 */
class LibrarySourceRegistry(
    private val sources: List<LibrarySource>
) {
    fun all(): List<LibrarySource> = sources

    fun find(id: String): LibrarySource? = sources.firstOrNull { it.id == id }
}
