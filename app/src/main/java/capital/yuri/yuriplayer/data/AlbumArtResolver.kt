package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Album art decode order:
 * preferred, disk cache, image albumArtUri, folder cover, SAF cover,
 * MMR embedded, then jaudiotagger embedded (File or one SAF extract per album).
 *
 * Never feeds audio MIME streams into BitmapFactory or Coil. That produces
 * OpenGL "Failed to create image decoder with message unimplemented".
 */
object AlbumArtResolver {

    private const val TAG = "AlbumArtResolver"
    private const val MAX_SAF_EXTRACT_BYTES = 120L * 1024L * 1024L

    private val albumLocks = ConcurrentHashMap<String, Mutex>()

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
            if (preferred != null) return@withContext scaleDown(preferred, maxSize)

            loadDiskAlbumCache(context, song, maxSize)?.let {
                return@withContext scaleDown(it, maxSize)
            }

            loadImageUri(context, song.albumArtUri, maxSize)?.also {
                cacheToDisk(context, song, it)
                return@withContext scaleDown(it, maxSize)
            }

            loadFolderCover(song.path, maxSize)?.also {
                cacheToDisk(context, song, it)
                return@withContext scaleDown(it, maxSize)
            }

            loadSafFolderCover(context, song, maxSize)?.also {
                cacheToDisk(context, song, it)
                return@withContext scaleDown(it, maxSize)
            }

            loadEnrichedCover(context, song, maxSize)?.let {
                return@withContext scaleDown(it, maxSize)
            }

            val aKey = albumKey(song.album, song.effectiveAlbumArtist).ifBlank { song.songKey }
            val lock = albumLocks.getOrPut(aKey) { Mutex() }
            lock.withLock {
                loadDiskAlbumCache(context, song, maxSize)?.let {
                    return@withContext scaleDown(it, maxSize)
                }
                loadEmbeddedMmr(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                    return@withContext scaleDown(it, maxSize)
                }
                loadEmbeddedJaudio(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                    Log.i(TAG, "jaudio art ok album=$aKey")
                    return@withContext scaleDown(it, maxSize)
                }
            }

            null
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

    fun extractEmbeddedToFile(file: File, dest: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val bmp = extractJaudioArtwork(file, 512) ?: return false
        return try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "tmp-${System.nanoTime()}.jpg")
            tmp.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
            dest.isFile && dest.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "extractEmbeddedToFile failed", e)
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
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
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
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
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

    private fun loadEmbeddedJaudio(context: Context, song: Song, maxSize: Int): Bitmap? {
        val path = song.path
        if (path != null && isVirtualLibraryPath(path)) return null

        if (!path.isNullOrBlank() && !path.contains("://")) {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                extractJaudioArtwork(f, maxSize)?.let { return it }
            }
        }

        if (song.contentUri.scheme.equals("file", true)) {
            val p = song.contentUri.path
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.isFile && f.canRead()) {
                    extractJaudioArtwork(f, maxSize)?.let { return it }
                }
            }
        }

        if (song.contentUri.scheme.equals("content", true)) {
            return extractJaudioFromContentUri(context, song.contentUri, maxSize)
        }

        return null
    }

    private fun extractJaudioArtwork(file: File, maxSize: Int): Bitmap? {
        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag ?: run {
                Log.d(TAG, "jaudio no tag ${file.name}")
                return null
            }
            val art = tag.firstArtwork ?: run {
                Log.d(TAG, "jaudio no artwork ${file.name}")
                return null
            }
            val bytes = art.binaryData ?: return null
            if (bytes.isEmpty()) return null
            decodeBytesSampled(bytes, maxSize)
        } catch (t: Throwable) {
            Log.w(TAG, "jaudio art failed ${file.name}: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun extractJaudioFromContentUri(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val size = querySize(context, uri)
        if (size != null && size > MAX_SAF_EXTRACT_BYTES) {
            Log.w(TAG, "skip jaudio SAF extract size=$size > $MAX_SAF_EXTRACT_BYTES")
            return null
        }

        val ext = guessAudioExt(context, uri)
        val tmp = File.createTempFile("yp_art_", ".$ext", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (tmp.length() == 0L) return null
            extractJaudioArtwork(tmp, maxSize)
        } catch (t: Throwable) {
            Log.w(TAG, "jaudio SAF art failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i < 0) return@use null
                c.getLong(i).takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessAudioExt(context: Context, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        when (mime) {
            "audio/flac" -> return "flac"
            "audio/mpeg", "audio/mp3" -> return "mp3"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> return "m4a"
            "audio/ogg", "audio/opus" -> return "ogg"
            "audio/wav", "audio/x-wav" -> return "wav"
        }
        val fromName = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.substringAfterLast('%')
            ?.lowercase()
            ?.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null && fromName in setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "wma")) {
            return fromName
        }
        val decoded = runCatching { Uri.decode(uri.toString()) }.getOrNull().orEmpty()
        for (ext in listOf("flac", "mp3", "m4a", "ogg", "opus", "wav")) {
            if (decoded.contains(".$ext", ignoreCase = true)) return ext
        }
        return "flac"
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

    private fun loadSafFolderCover(context: Context, song: Song, maxSize: Int): Bitmap? {
        val uri = song.contentUri
        if (!uri.scheme.equals("content", true)) return null
        return try {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            val parent = doc.parentFile ?: return null
            for (name in FOLDER_COVERS) {
                val cover = parent.findFile(name) ?: continue
                if (!cover.isFile) continue
                if (!isLikelyImageUri(context, cover.uri)) continue
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
        return loadImageUri(context, Uri.parse(trimmed), maxSize)
    }

    private fun loadImageUri(context: Context, uri: Uri?, maxSize: Int): Bitmap? {
        if (uri == null) return null
        if (!isLikelyImageUri(context, uri)) {
            Log.d(TAG, "skip non-image uri $uri")
            return null
        }
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
                Log.d(TAG, "loadImageUri failed: ${e.message}")
                null
            }
        }
    }

    private fun isLikelyImageUri(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return true
        if (scheme == "file" || scheme.isNullOrEmpty()) {
            val p = (uri.path ?: uri.toString()).lowercase()
            return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") ||
                p.endsWith(".webp") || p.endsWith(".bmp") || p.endsWith(".gif")
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase()
        if (mime != null) {
            if (mime.startsWith("audio/")) return false
            if (mime.startsWith("image/")) return true
        }
        val s = uri.toString().lowercase()
        if (s.contains("albumart")) return true
        if (s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp")) return true
        if (mime == null && scheme == "content") return true
        return false
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
