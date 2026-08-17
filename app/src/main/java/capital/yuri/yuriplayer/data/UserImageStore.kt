package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Copies picker content:// (or other transient) URIs into app-private storage
 * so playlist covers, artist profile pics, and banners survive restarts and
 * revoked grant permissions.
 */
class UserImageStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /**
     * @param sourceUri content:// or file:// from the system picker
     * @param namespace subdirectory under files/user_images (e.g. "playlists", "artists")
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
        val safeKey = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        // Replace any previous image for this key
        dir.listFiles()?.filter { it.name.startsWith("${safeKey}.") }?.forEach { it.delete() }

        val ext = guessExtension(context, src)
        val dest = File(dir, "${safeKey}.${ext}")

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

    /** Delete persisted image(s) for a key. */
    suspend fun delete(namespace: String, key: String) = withContext(Dispatchers.IO) {
        val dir = File(root, namespace)
        if (!dir.isDirectory) return@withContext
        val safeKey = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        dir.listFiles()?.filter { it.name.startsWith("${safeKey}.") }?.forEach { it.delete() }
    }

    companion object {
        const val DIR = "user_images"
        const val NS_PLAYLISTS = "playlists"
        const val NS_ARTISTS = "artists"
        const val NS_ARTIST_BANNERS = "artist_banners"

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
