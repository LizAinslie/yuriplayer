package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Album art decode: embedded tags → folder cover → MediaStore URI.
 * Caching lives in [AlbumArtCache] (4-slot); this object only decodes.
 */
object AlbumArtResolver {

    suspend fun load(context: Context, song: Song, maxSize: Int = 512): Bitmap? =
        loadUncached(context, song, maxSize)

    suspend fun loadUncached(context: Context, song: Song, maxSize: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
            val bitmap = loadEmbedded(song.path)
                ?: loadFolderCover(song.path)
                ?: loadUri(context, song.albumArtUri)
            bitmap?.let { scaleDown(it, maxSize) }
        }

    private fun loadEmbedded(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadFolderCover(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val dir = File(path).parentFile ?: return null
        val candidates = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png",
            "AlbumArt.jpg", "AlbumArt.png",
            "front.jpg", "front.png"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile) {
                return try {
                    BitmapFactory.decodeFile(f.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
        }
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name.lowercase()
            if (n == "cover.jpg" || n == "cover.jpeg" || n == "cover.png" ||
                n == "folder.jpg" || n == "folder.png"
            ) {
                return try {
                    BitmapFactory.decodeFile(f.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun loadUri(context: Context, uri: Uri?): Bitmap? {
        if (uri == null) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSize && h <= maxSize) return src
        val scale = maxSize.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
