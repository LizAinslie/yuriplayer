package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies picker content:// (or other transient) URIs into app-private storage
 * so playlist covers, artist profile pics, and banners survive restarts and
 * revoked grant permissions.
 *
 * Each persist uses a unique timestamped filename so Coil / UI models always
 * see a new URI when the user replaces an image (same stable key otherwise).
 *
 * Manual clears are remembered via a `.cleared` marker file so auto-fetch /
 * provider resolve will not re-populate that slot until the user sets a new image.
 *
 * [persistSlot] keeps sibling slots under the same key (multi playlist covers).
 */
class UserImageStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /**
     * @param sourceUri content:// or file:// from the system picker / crop
     * @param namespace subdirectory under files/user_images
     * @param key stable id used in the filename (playlist id / artist key)
     * @return file:// URI string of the persisted copy, or null on failure
     */
    suspend fun persist(
        sourceUri: String,
        namespace: String,
        key: String
    ): String? = withContext(Dispatchers.IO) {
        val src = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return@withContext null
        val dir = File(root, namespace).also { if (!it.exists()) it.mkdirs() }
        val safeKey = safe(key)

        // User is choosing art again — drop any prior clear veto
        clearedMarker(dir, safeKey).delete()

        // Drop previous image versions for this key first
        dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("$safeKey.") && !it.name.endsWith(CLEARED_SUFFIX)
        }?.forEach { it.delete() }

        val ext = guessExtension(context, src)
        val dest = File(dir, "$safeKey.${System.currentTimeMillis()}.$ext")

        try {
            context.contentResolver.openInputStream(src)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (!dest.isFile || dest.length() == 0L) {
                dest.delete()
                return@withContext null
            }
            Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    /**
     * Persist under `key-slotId` without deleting other slots of [key].
     * Used for multiple playlist covers.
     */
    suspend fun persistSlot(
        sourceUri: String,
        namespace: String,
        key: String,
        slotId: String
    ): String? = withContext(Dispatchers.IO) {
        val src = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return@withContext null
        val dir = File(root, namespace).also { if (!it.exists()) it.mkdirs() }
        val safeKey = safe("$key-$slotId")

        dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("$safeKey.") && !it.name.endsWith(CLEARED_SUFFIX)
        }?.forEach { it.delete() }

        val ext = guessExtension(context, src)
        val dest = File(dir, "$safeKey.${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(src)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (!dest.isFile || dest.length() == 0L) {
                dest.delete()
                return@withContext null
            }
            Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    suspend fun deleteSlot(namespace: String, key: String, slotId: String) =
        withContext(Dispatchers.IO) {
            val dir = File(root, namespace)
            if (!dir.isDirectory) return@withContext
            val safeKey = safe("$key-$slotId")
            dir.listFiles()?.filter {
                it.isFile && it.name.startsWith("$safeKey.") && !it.name.endsWith(CLEARED_SUFFIX)
            }?.forEach { it.delete() }
        }

    /** Existing persisted file:// URI for [key], or null (ignores clear markers). */
    fun resolve(namespace: String, key: String): String? {
        val dir = File(root, namespace)
        if (!dir.isDirectory) return null
        val safeKey = safe(key)
        if (clearedMarker(dir, safeKey).isFile) return null
        val file = dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("$safeKey.") &&
                    !it.name.endsWith(CLEARED_SUFFIX) &&
                    it.length() > 0L
            }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return Uri.fromFile(file).toString()
    }

    /** True if the user explicitly cleared this slot (do not auto-set). */
    fun isCleared(namespace: String, key: String): Boolean {
        val dir = File(root, namespace)
        if (!dir.isDirectory) return false
        return clearedMarker(dir, safe(key)).isFile
    }

    /**
     * Record a forced clear: delete any local image and write a durable marker
     * so providers / auto-fetch leave the slot empty until the user sets art again.
     */
    suspend fun markCleared(namespace: String, key: String) = withContext(Dispatchers.IO) {
        val dir = File(root, namespace).also { if (!it.exists()) it.mkdirs() }
        val safeKey = safe(key)
        dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("$safeKey.") && !it.name.endsWith(CLEARED_SUFFIX)
        }?.forEach { it.delete() }
        val marker = clearedMarker(dir, safeKey)
        if (!marker.exists()) {
            runCatching { marker.writeText("1") }
        }
    }

    /** Remove clear marker only (used if we need to unlock without setting art). */
    suspend fun clearClearedFlag(namespace: String, key: String) = withContext(Dispatchers.IO) {
        val dir = File(root, namespace)
        if (!dir.isDirectory) return@withContext
        clearedMarker(dir, safe(key)).delete()
    }

    /** Delete persisted image(s) for a key (does not write a clear marker). */
    suspend fun delete(namespace: String, key: String) = withContext(Dispatchers.IO) {
        val dir = File(root, namespace)
        if (!dir.isDirectory) return@withContext
        val safeKey = safe(key)
        dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("$safeKey.") && !it.name.endsWith(CLEARED_SUFFIX)
        }?.forEach { it.delete() }
    }

    private fun clearedMarker(dir: File, safeKey: String): File =
        File(dir, "$safeKey$CLEARED_SUFFIX")

    companion object {
        const val DIR = "user_images"
        const val NS_PLAYLISTS = "playlists"
        const val NS_ARTISTS = "artists"
        const val NS_ARTIST_BANNERS = "artist_banners"
        private const val CLEARED_SUFFIX = ".cleared"

        private fun safe(key: String): String =
            key.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        private fun guessExtension(context: Context, uri: Uri): String {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            return when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/jpeg", "image/jpg" -> "jpg"
                else -> {
                    val path = uri.lastPathSegment.orEmpty().lowercase()
                    when {
                        path.endsWith(".png") -> "png"
                        path.endsWith(".webp") -> "webp"
                        path.endsWith(".gif") -> "gif"
                        else -> "jpg"
                    }
                }
            }
        }
    }
}
