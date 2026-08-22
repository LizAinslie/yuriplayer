package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import capital.yuri.yuriplayer.core.log.yuriLog
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Album art pipeline. Successful local extracts always land in
 * [filesDir]/covers] keyed by albumKey so later opens skip re-decode.
 *
 * For SAF FLACs, [MediaMetadataRetriever] usually fails; we stream-parse
 * FLAC METADATA_BLOCK_PICTURE from the head of the file (no full copy).
 */
object AlbumArtResolver {

    private val log = yuriLog("AlbumArtResolver")
    private const val MAX_SAF_EXTRACT_BYTES = 80L * 1024L * 1024L
    /** Master cover stored under filesDir/covers — big enough for now-playing. */
    private const val PERSISTENT_COVER_SIZE = 1024
    /** Don't persist list-row thumbs as the album master. */
    private const val MIN_PERSIST_DIM = 256

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
            if (preferred != null) {
                cacheToDisk(context, song, preferred)
                return@withContext scaleDown(preferred, maxSize)
            }

            loadDiskAlbumCache(context, song, maxSize, minDim = maxSize)?.let {
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

            loadEnrichedCover(context, song, maxSize, minDim = maxSize)?.let {
                return@withContext scaleDown(it, maxSize)
            }

            val aKey = albumKey(song.album, song.effectiveAlbumArtist).ifBlank { song.songKey }
            val lock = albumLocks.getOrPut(aKey) { Mutex() }
            lock.withLock {
                loadDiskAlbumCache(context, song, maxSize, minDim = maxSize)?.let {
                    return@withContext scaleDown(it, maxSize)
                }

                // Stream FLAC picture first (SAF-friendly, no full-file copy)
                loadStreamEmbedded(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                    log.i { "stream art ok album=$aKey" }
                    return@withContext scaleDown(it, maxSize)
                }

                loadEmbeddedMmr(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                    log.i { "MMR art ok album=$aKey" }
                    return@withContext scaleDown(it, maxSize)
                }

                loadEmbeddedJaudio(context, song, maxSize)?.also {
                    cacheToDisk(context, song, it)
                    log.i { "jaudio art ok album=$aKey" }
                    return@withContext scaleDown(it, maxSize)
                }

                // Last resort: an undersized cached cover beats a blank now-playing card.
                loadDiskAlbumCache(context, song, maxSize, minDim = 0)?.let {
                    log.w { "undersized cache art album=$aKey ${it.width}x${it.height} want=$maxSize" }
                    return@withContext scaleDown(it, maxSize)
                }

                log.w { "no art for album=$aKey path=${song.path} uri=${song.contentUri}" }
            }

            null
        }

    suspend fun downloadToFile(url: String, dest: File, maxSize: Int = 512): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bmp = openHttp(httpUrlForSize(url, maxSize))?.use { decodeStreamSampled(it, maxSize) }
                    ?: return@withContext false
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
                log.w(e) { "downloadToFile failed $url" }
                false
            }
        }

    fun extractEmbeddedToFile(file: File, dest: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val bmp = extractJaudioArtwork(file, PERSISTENT_COVER_SIZE)
            ?: file.inputStream().use { extractFlacPicture(it)?.let { bytes -> decodeBytesSampled(bytes, PERSISTENT_COVER_SIZE) } }
            ?: return false
        return writeBitmapFile(bmp, dest)
    }

    fun diskCoverFile(context: Context, albumKeyStr: String): File {
        val name = MetadataEnrichmentService.sanitizeFileName(albumKeyStr) + ".jpg"
        return File(File(context.filesDir, "covers").also { it.mkdirs() }, name)
    }

    private fun diskCacheFile(context: Context, song: Song): File {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        return if (key.isNotBlank() && key != "|") {
            diskCoverFile(context, key)
        } else {
            val name = "song-" + MetadataEnrichmentService.sanitizeFileName(song.songKey) + ".jpg"
            File(File(context.filesDir, "covers").also { it.mkdirs() }, name)
        }
    }

    private fun loadDiskAlbumCache(
        context: Context,
        song: Song,
        maxSize: Int,
        minDim: Int = 0
    ): Bitmap? {
        val f = diskCacheFile(context, song)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val dim = maxOf(bounds.outWidth, bounds.outHeight)
            if (dim <= 0) return null
            if (minDim > 0 && dim < minDim) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheToDisk(context: Context, song: Song, bmp: Bitmap) {
        if (bmp.isRecycled) return
        val incoming = maxOf(bmp.width, bmp.height)
        if (incoming < MIN_PERSIST_DIM) return
        val dest = diskCacheFile(context, song)
        if (dest.isFile && dest.length() > 0L) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, bounds)
            val existing = maxOf(bounds.outWidth, bounds.outHeight)
            if (existing >= incoming) return
        }
        writeBitmapFile(bmp, dest)
    }

    private fun writeBitmapFile(bmp: Bitmap, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "tmp-${System.nanoTime()}.jpg")
            tmp.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.isFile && dest.length() > 0L
        } catch (e: Exception) {
            log.w(e) { "writeBitmapFile failed" }
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

    private fun loadEnrichedCover(context: Context, song: Song, maxSize: Int, minDim: Int = 0): Bitmap? {
        val key = albumKey(song.album, song.effectiveAlbumArtist)
        val f = diskCoverFile(context, key)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val dim = maxOf(bounds.outWidth, bounds.outHeight)
            if (dim <= 0) return null
            if (minDim > 0 && dim < minDim) return null
            decodeFileSampled(f.absolutePath, maxSize)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Open content/file stream and pull embedded picture without a full temp copy.
     * FLAC: METADATA_BLOCK_PICTURE. MP3: ID3v2 APIC near the head.
     */
    private fun loadStreamEmbedded(context: Context, song: Song, maxSize: Int): Bitmap? {
        val path = song.path
        if (path != null && isVirtualLibraryPath(path)) return null

        // Real file path
        if (!path.isNullOrBlank() && !path.contains("://")) {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                f.inputStream().use { streamEmbeddedFrom(it, f.name, maxSize) }?.let { return it }
            }
        }

        val uri = when {
            song.contentUri.scheme.equals("content", true) -> song.contentUri
            song.contentUri.scheme.equals("file", true) -> song.contentUri
            !path.isNullOrBlank() && path.contains("://") -> Uri.parse(path)
            else -> null
        } ?: return null

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                streamEmbeddedFrom(input, uri.toString(), maxSize)
            }
        } catch (e: Exception) {
            log.d { "stream open failed: ${e.message}" }
            null
        }
    }

    private fun streamEmbeddedFrom(input: InputStream, label: String, maxSize: Int): Bitmap? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(512 * 1024)
        // Probe magic
        val header = ByteArray(4)
        val n = buffered.read(header)
        if (n < 4) return null
        buffered.reset()

        val isFlac = header[0] == 'f'.code.toByte() &&
            header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() &&
            header[3] == 'C'.code.toByte()
        val isId3 = header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()

        val bytes = when {
            isFlac -> extractFlacPicture(buffered)
            isId3 -> extractId3Apic(buffered)
            else -> null
        }
        if (bytes == null) {
            log.d { "stream no picture for $label flac=$isFlac id3=$isId3" }
            return null
        }
        return decodeBytesSampled(bytes, maxSize)
    }

    /**
     * Walk FLAC metadata blocks; return first PICTURE (type 6) image payload.
     * Only reads the metadata region at the start of the file.
     */
    private fun extractFlacPicture(input: InputStream): ByteArray? {
        return try {
            val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
            val magic = ByteArray(4)
            din.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "fLaC") return null

            var last = false
            var blocks = 0
            while (!last && blocks < 64) {
                blocks++
                val header = din.readInt()
                last = (header ushr 31) == 1
                val type = (header ushr 24) and 0x7F
                val length = header and 0x00FFFFFF
                if (length < 0 || length > 32 * 1024 * 1024) return null

                if (type == 6) {
                    // PICTURE block
                    if (length < 32) {
                        din.skipBytes(length)
                        continue
                    }
                    din.readInt() // picture type
                    val mimeLen = din.readInt()
                    if (mimeLen < 0 || mimeLen > 256) return null
                    din.skipBytes(mimeLen)
                    val descLen = din.readInt()
                    if (descLen < 0 || descLen > 4096) return null
                    din.skipBytes(descLen)
                    din.readInt() // width
                    din.readInt() // height
                    din.readInt() // depth
                    din.readInt() // colors
                    val dataLen = din.readInt()
                    if (dataLen <= 0 || dataLen > 16 * 1024 * 1024) return null
                    val data = ByteArray(dataLen)
                    din.readFully(data)
                    return data
                } else {
                    din.skipBytes(length)
                }
            }
            null
        } catch (e: Exception) {
            log.d { "FLAC picture parse failed: ${e.message}" }
            null
        }
    }

    /**
     * Minimal ID3v2 APIC extract (JPEG/PNG). Reads only the tag at file start.
     */
    private fun extractId3Apic(input: InputStream): ByteArray? {
        return try {
            val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
            val header = ByteArray(10)
            din.readFully(header)
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) return null
            val ver = header[3].toInt() and 0xFF
            // synchsafe size
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            if (tagSize <= 0 || tagSize > 4 * 1024 * 1024) return null

            var remaining = tagSize
            while (remaining > 10) {
                val frameId = ByteArray(4)
                din.readFully(frameId)
                remaining -= 4
                val frameSize = if (ver >= 4) {
                    val b = ByteArray(4)
                    din.readFully(b)
                    remaining -= 4
                    ((b[0].toInt() and 0x7F) shl 21) or
                        ((b[1].toInt() and 0x7F) shl 14) or
                        ((b[2].toInt() and 0x7F) shl 7) or
                        (b[3].toInt() and 0x7F)
                } else {
                    din.readInt().also { remaining -= 4 }
                }
                din.readUnsignedShort() // flags
                remaining -= 2
                if (frameSize <= 0 || frameSize > remaining) return null

                val id = String(frameId, Charsets.US_ASCII)
                if (id == "APIC") {
                    val body = ByteArray(frameSize)
                    din.readFully(body)
                    return parseApicPayload(body)
                }
                din.skipBytes(frameSize)
                remaining -= frameSize
            }
            null
        } catch (e: Exception) {
            log.d { "ID3 APIC parse failed: ${e.message}" }
            null
        }
    }

    private fun parseApicPayload(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        var i = 0
        val encoding = body[i++].toInt() and 0xFF
        // MIME null-terminated (latin1)
        while (i < body.size && body[i] != 0.toByte()) i++
        i++ // null
        if (i >= body.size) return null
        i++ // picture type
        // description terminated per encoding
        when (encoding) {
            0, 3 -> { // ISO-8859-1 / UTF-8
                while (i < body.size && body[i] != 0.toByte()) i++
                i++
            }
            1, 2 -> { // UTF-16
                while (i + 1 < body.size && !(body[i] == 0.toByte() && body[i + 1] == 0.toByte())) i += 2
                i += 2
            }
            else -> return null
        }
        if (i >= body.size) return null
        return body.copyOfRange(i, body.size)
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
            log.d { "MMR embedded failed: ${e.message}" }
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
            val tag = audio.tag ?: return null
            val art = tag.firstArtwork ?: return null
            val bytes = art.binaryData ?: return null
            if (bytes.isEmpty()) return null
            decodeBytesSampled(bytes, maxSize)
        } catch (t: Throwable) {
            log.w { "jaudio art failed ${file.name}: ${t.javaClass.simpleName}: ${t.message}" }
            null
        }
    }

    private fun extractJaudioFromContentUri(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val size = querySize(context, uri)
        if (size != null && size > MAX_SAF_EXTRACT_BYTES) {
            log.w { "skip jaudio SAF extract size=$size" }
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
            log.w { "jaudio SAF art failed: ${t.javaClass.simpleName}: ${t.message}" }
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
        val decoded = runCatching { Uri.decode(uri.toString()) }.getOrNull().orEmpty().lowercase()
        for (ext in listOf("flac", "mp3", "m4a", "ogg", "opus", "wav")) {
            if (decoded.contains(".$ext")) return ext
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
            log.d { "SAF folder cover failed: ${e.message}" }
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
            log.d { "skip non-image uri $uri" }
            return null
        }
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            null, "" -> {
                val path = uri.path ?: uri.toString()
                if (path.startsWith("/")) decodeFileSampled(path, maxSize) else null
            }
            "http", "https" -> openHttp(httpUrlForSize(uri.toString(), maxSize))?.use { decodeStreamSampled(it, maxSize) }
            "file" -> {
                val path = uri.path ?: return null
                decodeFileSampled(path, maxSize)
            }
            else -> try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    decodeStreamSampled(input, maxSize)
                }
            } catch (e: Exception) {
                log.d { "loadImageUri failed: ${e.message}" }
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

    /**
     * Jellyfin bakes `maxWidth=512` and Subsonic bakes `size=512` into
     * [Song.albumArtUri] at index time. Rewrite those to the decode tier so
     * list rows stay cheap and now-playing can request HQ.
     */
    private fun httpUrlForSize(url: String, maxSize: Int): String {
        if (maxSize <= 0) return url
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return url
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return url
        val names = uri.queryParameterNames
        if (names.isEmpty()) return url
        val b = uri.buildUpon().clearQuery()
        var touched = false
        for (name in names) {
            val raw = uri.getQueryParameter(name) ?: continue
            if (name.lowercase() in SIZE_QUERY_KEYS) {
                b.appendQueryParameter(name, maxSize.toString())
                touched = true
            } else {
                b.appendQueryParameter(name, raw)
            }
        }
        return if (touched) b.build().toString() else url
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
                log.w { "HTTP $code for $url" }
                conn.disconnect()
                return null
            }
            conn.inputStream
        } catch (e: Exception) {
            log.w(e) { "openHttp failed $url" }
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

    private val SIZE_QUERY_KEYS = setOf(
        "maxwidth", "max_width", "maxheight", "max_height", "size", "width"
    )
}
