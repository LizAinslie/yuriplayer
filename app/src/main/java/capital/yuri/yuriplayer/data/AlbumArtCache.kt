package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Cover cache for now-playing / peek transitions.
 *
 * - Memory: hard cap [MAX_MEMORY] bitmaps (LRU)
 * - Disk: JPEG files under cacheDir/album_art (survives process death)
 * - Identical art (same folder cover / album identity) shares one key
 */
class AlbumArtCache(
    context: Context
) {
    private val appContext = context.applicationContext
    private val diskDir = File(appContext.cacheDir, "album_art").also { it.mkdirs() }
    private val lock = Mutex()

    /** Access-ordered: eldest evicted when over capacity. */
    private val map = object : LinkedHashMap<String, Bitmap>(MAX_MEMORY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val drop = size > MAX_MEMORY
            if (drop) Log.d(TAG, "mem-evict ${eldest?.key}")
            return drop
        }
    }

    fun artKey(song: Song): String {
        val path = song.path
        if (path != null) {
            val parent = File(path).parentFile
            if (parent != null) {
                for (name in COVER_NAMES) {
                    val cover = File(parent, name)
                    if (cover.isFile && cover.length() > 0) {
                        return "cover:${cover.absolutePath}:${cover.length()}"
                    }
                }
            }
        }
        val album = song.album?.trim()?.lowercase().orEmpty()
        val artist = (song.albumArtist ?: song.artist)?.trim()?.lowercase().orEmpty()
        if (album.isNotEmpty()) return "album:$album|$artist"
        return "song:${song.path ?: song.contentUri}"
    }

    private fun diskFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
            .take(40)
        return File(diskDir, "$digest.jpg")
    }

    private fun readDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.takeIf { !it.isRecycled }
        } catch (e: Exception) {
            Log.w(TAG, "disk-read failed $key", e)
            null
        }
    }

    private fun writeDisk(key: String, bmp: Bitmap) {
        if (bmp.isRecycled) return
        try {
            val target = diskFile(key)
            val tmp = File(diskDir, "tmp-${Thread.currentThread().id}-${System.nanoTime()}.jpg")
            tmp.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            trimDiskIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "disk-write failed $key", e)
        }
    }

    /** Keep disk cache bounded (~MAX_DISK files, oldest by mtime). */
    private fun trimDiskIfNeeded() {
        val files = diskDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") && !f.name.startsWith("tmp-") }
            ?: return
        if (files.size <= MAX_DISK) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK)
            .forEach { it.delete() }
    }

    suspend fun get(context: Context, song: Song, maxSize: Int = 512): Bitmap? {
        val key = artKey(song)

        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
        }

        // Disk hit — fast path after process restart
        val fromDisk = withContext(Dispatchers.IO) { readDisk(key) }
        if (fromDisk != null) {
            lock.withLock {
                map[key]?.takeIf { !it.isRecycled }?.let { return it }
                map[key] = fromDisk
                Log.d(TAG, "disk-hit $key mem=${map.size}")
            }
            return fromDisk
        }

        val bmp = withContext(Dispatchers.IO) {
            AlbumArtResolver.loadUncached(context, song, maxSize)
        } ?: return null

        withContext(Dispatchers.IO) { writeDisk(key, bmp) }

        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
            map[key] = bmp
            Log.d(TAG, "decode-put $key mem=${map.size}")
        }
        return bmp
    }

    /**
     * Warm memory + disk for the given songs (deduped by art key).
     * Call as soon as the playback window is known so early skips still have art ready.
     */
    suspend fun prefetch(context: Context, songs: List<Song?>, maxSize: Int = 512) {
        val seen = mutableSetOf<String>()
        for (song in songs) {
            if (song == null) continue
            val key = artKey(song)
            if (!seen.add(key)) continue
            try {
                get(context, song, maxSize)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed $key", e)
            }
        }
    }

    suspend fun clearMemory() = lock.withLock { map.clear() }

    suspend fun clearAll() {
        lock.withLock { map.clear() }
        withContext(Dispatchers.IO) {
            diskDir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        private const val TAG = "YuriPlayer.ArtCache"
        const val MAX_MEMORY = 4
        const val MAX_DISK = 48
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png", "AlbumArt.jpg"
        )
    }
}
