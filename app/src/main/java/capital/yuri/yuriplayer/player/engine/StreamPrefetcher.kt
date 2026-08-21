package capital.yuri.yuriplayer.player.engine

import android.os.Handler
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the next HTTP track (Jellyfin original / static stream) to disk
 * while the current song plays, so swap can start from a local file.
 */
class StreamPrefetcher(
    cacheDir: File,
    private val ioHandler: Handler,
    private val mainHandler: Handler
) {
    private val dir = File(cacheDir, "stream_prefetch").also { it.mkdirs() }
    private val ready = object : LinkedHashMap<String, File>(MAX_FILES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?): Boolean {
            val drop = size > MAX_FILES
            if (drop) eldest?.value?.delete()
            return drop
        }
    }

    @Volatile private var generation = 0
    private var downloadingId: String? = null

    fun fileIfReady(mediaId: String): File? {
        val f = synchronized(ready) { ready[mediaId] }
        return f?.takeIf { it.isFile && it.length() >= MIN_BYTES }
    }

    fun start(item: PlaybackMedia, onReady: (File) -> Unit) {
        if (!item.isNetwork) return
        val scheme = item.uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        fileIfReady(item.mediaId)?.let {
            Log.i(TAG, "prefetch hit '${item.title}' ${it.length()}B")
            mainHandler.post { onReady(it) }
            return
        }
        if (downloadingId == item.mediaId) return
        generation += 1
        val gen = generation
        downloadingId = item.mediaId
        ioHandler.post { download(item, gen, onReady) }
    }

    fun cancel() {
        generation += 1
        downloadingId = null
    }

    fun release() {
        cancel()
        synchronized(ready) {
            ready.values.forEach { it.delete() }
            ready.clear()
        }
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun download(item: PlaybackMedia, gen: Int, onReady: (File) -> Unit) {
        val dest = File(dir, keyName(item.mediaId) + ".bin")
        val tmp = File(dir, keyName(item.mediaId) + ".part")
        var conn: HttpURLConnection? = null
        try {
            if (dest.isFile && dest.length() >= MIN_BYTES) {
                synchronized(ready) { ready[item.mediaId] = dest }
                if (gen == generation) mainHandler.post { onReady(dest) }
                return
            }
            tmp.delete()
            Log.i(TAG, "prefetch start '${item.title}'")
            conn = (URL(item.uri.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "YuriPlayer/0.1")
                item.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "prefetch HTTP $code '${item.title}'")
                return
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (total > MAX_BYTES) {
                Log.w(TAG, "prefetch skip '${item.title}' too large $total")
                return
            }
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (gen == generation) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > MAX_BYTES) {
                            Log.w(TAG, "prefetch abort '${item.title}' over cap")
                            tmp.delete()
                            return
                        }
                    }
                }
            }
            if (gen != generation) {
                tmp.delete()
                return
            }
            if (tmp.length() < MIN_BYTES) {
                tmp.delete()
                return
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            synchronized(ready) { ready[item.mediaId] = dest }
            Log.i(TAG, "prefetch ready '${item.title}' ${dest.length()}B of $total")
            mainHandler.post { onReady(dest) }
        } catch (e: Exception) {
            Log.w(TAG, "prefetch failed '${item.title}': ${e.message}")
            tmp.delete()
        } finally {
            runCatching { conn?.disconnect() }
            if (gen == generation && downloadingId == item.mediaId) downloadingId = null
        }
    }

    private fun keyName(mediaId: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val hex = md.digest(mediaId.toByteArray()).joinToString("") { "%02x".format(it) }
        return hex.take(24)
    }

    companion object {
        private const val TAG = "StreamPrefetch"
        private const val MIN_BYTES = 64 * 1024L
        private const val MAX_BYTES = 80L * 1024L * 1024L
        private const val MAX_FILES = 3
    }
}
