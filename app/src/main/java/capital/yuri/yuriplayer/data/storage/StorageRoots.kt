package capital.yuri.yuriplayer.data.storage

import android.content.Context
import capital.yuri.yuriplayer.data.LibrarySettings

/**
 * Builds [StorageRoot] instances from current settings.
 * Manual SAF trees only for now; WebDAV / Drive / Nextcloud mount later.
 */
object StorageRoots {
    fun fromSettings(context: Context, settings: LibrarySettings): List<StorageRoot> {
        val trees = settings.getManualTreeUris()
        return trees.mapNotNull { uri ->
            SafStorageRoot.fromTreeUri(context.applicationContext, uri)
        }
    }
}
