package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Album art decode order:
 * preferred → disk cache → MMR embedded → **jaudiotagger embedded** →
 * filesystem folder cover → SAF sibling cover → enriched → albumArtUri.
 *
 * MediaMetadataRetriever frequently returns null for FLAC/Opus embedded art
 * on Android; jaudiotagger is the reliable path when we have a real File
 * (or a temp copy from SAF).
 */
object AlbumArtResolver {

    private const val TAG = "AlbumArtResolver"

    init {
        runCatching { TagOptionSingleton.getInstance().isAndroid = true }
    }

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
                ?: loadDiskAlbumCache(context, song, maxSize)
                ?: loadEmbeddedMmr(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                }
                ?: loadEmbeddedJaudio(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                }
                ?: loadFolderCover(song.path, maxSize)?.also {
                    cacheToDisk(context, song, it)
                }
                ?: loadSafFolderCover(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                }
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

    private fun diskCacheFile(context: Context, song: Song): File {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val name = if (key.isNotBlank() && key != "|") {
            MetadataEnrichmentService.sanitizeFileName(key) + ".jpg"
        } else {
            "song-" + MetadataEnrichmentService.sanitizeFileName(song.songKey) + ".jpg"
        }
        return File(File(context.filesDir, "covers"), name)
    }

    private fun loadDiskAlbumCache(context: Context, song: Song, maxSize: Int): Bitmap? {
        val f = diskCacheFile(context, song)
        if (!f.isFile || f.length() == 0L) return null
        return decodeFileSampled(f.absolutePath, maxSize)
    }

    private fun cacheToDisk(context: Context, song: Song, bmp: Bitmap) {
        if (bmp.isRecycled) return
        try {
            val dest = diskCacheFile(context, song)
            if (dest.isFile && dest.length() > 0L) return
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "tmp-${System.nanoTime()}.jpg")
            tmp.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.d(TAG, "cacheToDisk failed: ${e.message}")
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
            decodeBytesSampled(bytes, maxSize)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBytesSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        return try {
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

    private fun loadEmbeddedMmr(context: Context, song: Song, maxSize: Int): Bitmap? {
        val path = song.path
        if (path != null && isVirtualLibraryPath(path)) return null

        val retriever = MediaMetadataRetriever()
        return try {
            if (!openRetriever(context, song, retriever)) return null
            val bytes = retriever.embeddedPicture ?: return null
            decodeBytesSampled(bytes, maxSize)
        } catch (e: Exception) {
            Log.d(TAG, "MMR embedded failed: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun openRetriever(context: Context, song: Song, retriever: MediaMetadataRetriever): Boolean {
        val path = song.path
        if (!path.isNullOrBlank() && !path.contains("://") && File(path).canRead()) {
            try {
                retriever.setDataSource(path)
                return true
            } catch (_: Exception) {
            }
        }
        if (!path.isNullOrBlank() && path.contains("://")) {
            try {
                retriever.setDataSource(context, Uri.parse(path))
                return true
            } catch (_: Exception) {
            }
        }
        val uri = song.contentUri
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file") {
            try {
                retriever.setDataSource(context, uri)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * jaudiotagger reads FLAC/MP3/Ogg art where MMR returns null.
     * Uses a real File when available; for SAF content:// copies to a temp file
     * once (result is disk-cached by albumKey for subsequent loads).
     */
    private fun loadEmbeddedJaudio(context: Context, song: Song, maxSize: Int): Bitmap? {
        val path = song.path
        if (path != null && isVirtualLibraryPath(path)) return null

        // Real filesystem path
        if (!path.isNullOrBlank() && !path.contains("://")) {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                extractJaudioArtwork(f, maxSize)?.let { return it }
            }
        }

        // file:// URI
        if (song.contentUri.scheme.equals("file", true)) {
            val p = song.contentUri.path
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.isFile && f.canRead()) {
                    extractJaudioArtwork(f, maxSize)?.let { return it }
                }
            }
        }

        // SAF content:// — stream to temp with a real extension so jaudiotagger
        // picks the right reader, extract art, delete temp.
        if (song.contentUri.scheme.equals("content", true)) {
            return extractJaudioFromContentUri(context, song.contentUri, maxSize)
        }

        return null
    }

    private fun extractJaudioArtwork(file: File, maxSize: Int): Bitmap? {
        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag ?: return null
            val art = tag.firstArtwork ?: return null
            val bytes = art.binaryData ?: return null
            if (bytes.isEmpty()) return null
            decodeBytesSampled(bytes, maxSize)
        } catch (t: Throwable) {
            Log.d(TAG, "jaudio art failed ${file.name}: ${t.message}")
            null
        }
    }

    private fun extractJaudioFromContentUri(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val ext = guessAudioExt(context, uri)
        val tmp = File.createTempFile("yp_art_", ".$ext", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (tmp.length() == 0L) return null
            extractJaudioArtwork(tmp, maxSize)
        } catch (t: Throwable) {
            Log.d(TAG, "jaudio SAF art failed: ${t.message}")
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun guessAudioExt(context: Context, uri: Uri): String {
        val fromName = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null) return fromName
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when (mime) {
            "audio/flac" -> "flac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            else -> "flac"
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
        for (name in FOLDER_COVERS) {
            val f = File(dir, name)
            if (f.isFile) {
                decodeFileSampled(f.absolutePath, maxSize)?.let { return it }
            }
        }
        return null
    }

    /** Sibling cover.jpg next to a SAF audio document. */
    private fun loadSafFolderCover(context: Context, song: Song, maxSize: Int): Bitmap? {
        val uri = song.contentUri
        if (!uri.scheme.equals("content", true)) return null
        return try {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            val parent = doc.parentFile ?: return null
            for (name in FOLDER_COVERS) {
                val cover = parent.findFile(name) ?: continue
                if (!cover.isFile) continue
                context.contentResolver.openInputStream(cover.uri)?.use { input ->
                    decodeStreamSampled(input, maxSize)
                }?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "SAF folder cover failed: ${e.message}")
            null
        }
    }

    private fun loadAnyUri(context: Context, raw: String, maxSize: Int): Bitmap? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
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
                // Image content URI (MediaStore albumart, SAF cover.jpg)
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

    private val FOLDER_COVERS = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.png",
        "AlbumArt.jpg", "AlbumArt.png",
        "front.jpg", "front.png"
    )
}
