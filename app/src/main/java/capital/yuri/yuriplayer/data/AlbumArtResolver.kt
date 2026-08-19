package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Album art decode: embedded tags → folder cover → enriched file →
 * HTTP(S) remote URI (Jellyfin / Subsonic) → MediaStore / content URI.
 * Caching lives in [AlbumArtCache]; this object only decodes, subsampled to [maxSize].
 */
object AlbumArtResolver {

    private const val TAG = "AlbumArtResolver"

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

    /** Download a remote cover into [dest] (JPEG). Returns true on success. */
    suspend fun downloadToFile(url: String, dest: File, maxSize: Int = 512): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bmp = openHttp(url)?.use { decodeStreamSampled(it, maxSize) } ?: return@withContext false
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, "tmp-${System.nanoTime()}.jpg")
                tmp.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
                true
            } catch (e: Exception) {
                Log.w(TAG, "downloadToFile failed $url", e)
                false
            }
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
        // Virtual remote keys are not filesystem paths
        if (path.startsWith("jellyfin:") || path.startsWith("subsonic:") || path.startsWith("navidrome:")) {
            return null
        }
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
        if (path.startsWith("jellyfin:") || path.startsWith("subsonic:") || path.contains("://")) {
            return null
        }
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
        return null
    }

    private fun loadUri(context: Context, uri: Uri?, maxSize: Int): Bitmap? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "http", "https" -> openHttp(uri.toString())?.use { decodeStreamSampled(it, maxSize) }
            else -> try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    decodeStreamSampled(input, maxSize)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun openHttp(url: String): InputStream? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "YuriPlayer/0.1")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                conn.disconnect()
                return null
            }
            conn.inputStream
        } catch (e: Exception) {
            Log.w(TAG, "openHttp failed $url", e)
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
            try {
                src.recycle()
            } catch (_: Exception) {
            }
        }
        return scaled
    }
}
