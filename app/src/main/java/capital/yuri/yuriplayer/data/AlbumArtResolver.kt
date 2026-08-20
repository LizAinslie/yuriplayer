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
 * Album art decode: **preferred override** → embedded tags → folder cover →
 * enriched file → HTTP(S) remote URI → MediaStore / content URI.
 *
 * Preferred URIs may be bare absolute paths (custom upload into filesDir),
 * file://, content://, or http(s).
 */
object AlbumArtResolver {

    private const val TAG = "AlbumArtResolver"

    suspend fun load(context: Context, song: Song, maxSize: Int = 512): Bitmap? =
        loadUncached(context, song, maxSize, preferredUri = null)

    suspend fun loadUncached(
        context: Context,
        song: Song,
        maxSize: Int = 512,
        preferredUri: String? = null
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val preferred = preferredUri?.takeIf { it.isNotBlank() }
                ?.let { loadAnyUri(context, it, maxSize) }
            val bitmap = preferred
                ?: loadEmbedded(context, song, maxSize)
                ?: loadFolderCover(song.path, maxSize)
                ?: loadEnrichedCover(context, song, maxSize)
                ?: loadUri(context, song.albumArtUri, maxSize)
            bitmap?.let { scaleDown(it, maxSize) }
        }

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

    private fun loadEmbedded(context: Context, song: Song, maxSize: Int): Bitmap? {
        val path = song.path
        if (path != null && isVirtualLibraryPath(path)) return null

        val retriever = MediaMetadataRetriever()
        return try {
            var opened = false
            if (!path.isNullOrBlank() && !path.contains("://") && File(path).canRead()) {
                try {
                    retriever.setDataSource(path)
                    opened = true
                } catch (e: Exception) {
                    Log.d(TAG, "embedded path open failed: ${e.message}")
                }
            }
            if (!opened && !path.isNullOrBlank() && path.contains("://")) {
                try {
                    retriever.setDataSource(context, Uri.parse(path))
                    opened = true
                } catch (e: Exception) {
                    Log.d(TAG, "embedded path-uri open failed: ${e.message}")
                }
            }
            if (!opened) {
                val uri = song.contentUri
                val scheme = uri.scheme?.lowercase()
                if (scheme == "content" || scheme == "file") {
                    try {
                        retriever.setDataSource(context, uri)
                        opened = true
                    } catch (e: Exception) {
                        Log.d(TAG, "embedded contentUri open failed: ${e.message}")
                    }
                }
            }
            if (!opened) return null

            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Log.d(TAG, "embedded decode failed: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun isVirtualLibraryPath(path: String): Boolean =
        path.startsWith("jellyfin:", true) ||
            path.startsWith("subsonic:", true) ||
            path.startsWith("navidrome:", true)

    private fun loadFolderCover(path: String?, maxSize: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (isVirtualLibraryPath(path) || path.contains("://")) return null
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

    /** Preferred / stored URI string: bare path, file://, content://, http(s). */
    private fun loadAnyUri(context: Context, raw: String, maxSize: Int): Bitmap? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Custom upload saves absolute path without scheme
        if (trimmed.startsWith("/") && !trimmed.contains("://")) {
            return decodeFileSampled(trimmed, maxSize)
        }
        return loadUri(context, Uri.parse(trimmed), maxSize)
    }

    private fun loadUri(context: Context, uri: Uri?, maxSize: Int): Bitmap? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            null, "" -> {
                val path = uri.path ?: uri.toString()
                if (path.startsWith("/")) decodeFileSampled(path, maxSize) else null
            }
            "http", "https" -> openHttp(uri.toString())?.use { decodeStreamSampled(it, maxSize) }
            "file" -> {
                val path = uri.path ?: return null
                decodeFileSampled(path, maxSize)
            }
            else -> try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    decodeStreamSampled(input, maxSize)
                }
            } catch (e: Exception) {
                Log.d(TAG, "loadUri content failed: ${e.message}")
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
