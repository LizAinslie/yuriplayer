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
 * Album art decode: embedded tags → folder cover → enriched network file → MediaStore URI.
 * Caching lives in [AlbumArtCache]; this object only decodes, subsampled to [maxSize].
 */
object AlbumArtResolver {

    suspend fun load(context: Context, song: Song, maxSize: Int = 512): Bitmap? =
        loadUncached(context, song, maxSize)

    suspend fun loadUncached(context: Context, song: Song, maxSize: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
            val bitmap = loadEmbedded(song.path, maxSize)
                ?: loadFolderCover(song.path, maxSize)
                ?: loadEnrichedCover(context, song, maxSize)
                ?: loadUri(context, song.albumArtUri, maxSize)
            bitmap?.let { scaleDown(it, maxSize) }
        }

    private fun sampleSize(srcW: Int, srcH: Int, maxSize: Int): Int {
        if (srcW <= 0 || srcH <= 0 || maxSize <= 0) return 1
        var inSampleSize = 1
        var halfW = srcW / 2
        var halfH = srcH / 2
        while (halfW / inSampleSize >= maxSize && halfH / inSampleSize >= maxSize) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun decodeFileSampled(path: String, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeStreamSampled(input: InputStream, maxSize: Int): Bitmap? {
        return try {
            val bytes = input.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEnrichedCover(context: Context, song: Song, maxSize: Int): Bitmap? {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val name = MetadataEnrichmentService.sanitizeFileName(key) + ".jpg"
        val f = File(File(context.filesDir, "covers"), name)
        if (!f.isFile) return null
        return decodeFileSampled(f.absolutePath, maxSize)
    }

    private fun loadEmbedded(path: String?, maxSize: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadFolderCover(path: String?, maxSize: Int): Bitmap? {
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
                decodeFileSampled(f.absolutePath, maxSize)?.let { return it }
            }
        }
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name.lowercase()
            if (n == "cover.jpg" || n == "cover.jpeg" || n == "cover.png" ||
                n == "folder.jpg" || n == "folder.png"
            ) {
                decodeFileSampled(f.absolutePath, maxSize)?.let { return it }
            }
        }
        return null
    }

    private fun loadUri(context: Context, uri: Uri?, maxSize: Int): Bitmap? {
        if (uri == null) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                decodeStreamSampled(input, maxSize)
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
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (scaled !== src && !src.isRecycled) {
            // Source was only needed for the scale step.
            try {
                src.recycle()
            } catch (_: Exception) {
            }
        }
        return scaled
    }
}
