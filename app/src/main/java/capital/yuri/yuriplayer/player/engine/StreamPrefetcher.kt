package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads remote tracks to disk while they stream so skip-back / repeat
 * hit a local file. Playback itself uses the HTTP URI until a file is complete
 * (stream-until-buffered) — we never swap a playing decoder onto a partial file.
 */
class StreamPrefetcher private constructor(cacheDir: File) {
    private val dir = File(cacheDir, "stream_prefetch").also { it.mkdirs() }
    private val ioThread = HandlerThread("stream-prefetch").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    private val ready = object : LinkedHashMap<String, File>(MAX_FILES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?): Boolean {
            val drop = size > MAX_FILES
            if (drop) eldest?.value?.delete()
            return drop
        }
    }

    private val generations = HashMap<String, Int>()
    private val inFlight = HashSet<String>()

    fun fileIfReady(mediaId: String): File? {
        val f = synchronized(ready) { ready[mediaId] }
        return f?.takeIf { it.isFile && it.length() >= MIN_BYTES }
    }

    fun cached(item: PlaybackMedia): PlaybackMedia {
        val f = fileIfReady(item.mediaId) ?: return item
        return item.copy(uri = Uri.fromFile(f), isNetwork = false)
    }

    fun start(item: PlaybackMedia) {
        if (!item.isNetwork || item.live) return
        val scheme = item.uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        if (fileIfReady(item.mediaId) != null) return
        val gen = synchronized(generations) {
            if (item.mediaId in inFlight) return
            if (inFlight.size >= MAX_IN_FLIGHT) return
            val g = (generations[item.mediaId] ?: 0) + 1
            generations[item.mediaId] = g
            inFlight += item.mediaId
            g
        }
        ioHandler.post { download(item, gen) }
    }

    /** Drop in-flight jobs whose mediaId is not in [keep]. Cached files stay. */
    fun retain(keep: Set<String>) {
        synchronized(generations) {
            val drop = inFlight.filter { it !in keep }
            drop.forEach { id ->
                generations[id] = (generations[id] ?: 0) + 1
                inFlight.remove(id)
            }
        }
    }

    fun release() {
        synchronized(generations) {
            inFlight.forEach { id -> generations[id] = (generations[id] ?: 0) + 1 }
            inFlight.clear()
        }
        ioThread.quitSafely()
        synchronized(ready) {
            ready.clear()
        }
    }

    private fun download(item: PlaybackMedia, gen: Int) {
        val dest = File(dir, keyName(item.mediaId) + ".bin")
        val tmp = File(dir, keyName(item.mediaId) + ".part")
        var conn: HttpURLConnection? = null
        try {
            if (!stillCurrent(item.mediaId, gen)) return
            if (dest.isFile && dest.length() >= MIN_BYTES) {
                synchronized(ready) { ready[item.mediaId] = dest }
                return
            }
            tmp.delete()
            Log.i(TAG, "prefetch start '${item.title}'")
            conn = (URL(item.uri.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
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
                    while (stillCurrent(item.mediaId, gen)) {
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
            if (!stillCurrent(item.mediaId, gen)) {
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
        } catch (e: Exception) {
            Log.w(TAG, "prefetch failed '${item.title}': ${e.message}")
            tmp.delete()
        } finally {
            runCatching { conn?.disconnect() }
            synchronized(generations) {
                if (generations[item.mediaId] == gen) inFlight.remove(item.mediaId)
            }
        }
    }

    private fun stillCurrent(mediaId: String, gen: Int): Boolean =
        synchronized(generations) { generations[mediaId] == gen }

    private fun keyName(mediaId: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(mediaId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    companion object {
        private const val TAG = "StreamPrefetch"
        private const val MIN_BYTES = 256 * 1024L
        private const val MAX_BYTES = 256L * 1024L * 1024L
        private const val MAX_FILES = 8
        private const val MAX_IN_FLIGHT = 2

        @Volatile private var instance: StreamPrefetcher? = null

        fun get(context: Context): StreamPrefetcher {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: StreamPrefetcher(context.applicationContext.cacheDir).also {
                    instance = it
                }
            }
        }
    }
}
