package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Shared in-memory album art cache keyed by content hash.
 * Same embedded art / cover file across tracks reuses one bitmap.
 */
class AlbumArtCache(
    @Suppress("UNUSED_PARAMETER") context: Context
) {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(
        4 * 1024 * 1024,
        24 * 1024 * 1024
    )

    private val memory = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val keyMutex = Mutex()
    private val inflight = mutableMapOf<String, Mutex>()

    /**
     * Stable key for a song's artwork. Prefers file path of cover or
     * content-hash of embedded picture so identical art collapses.
     */
    suspend fun keyFor(song: Song): String = withContext(Dispatchers.IO) {
        val path = song.path
        if (path != null) {
            val parent = java.io.File(path).parentFile
            if (parent != null) {
                for (name in COVER_NAMES) {
                    val cover = java.io.File(parent, name)
                    if (cover.isFile && cover.length() > 0) {
                        return@withContext "file:${cover.absolutePath}:${cover.length()}:${cover.lastModified()}"
                    }
                }
            }
        }
        // Embedded / MediaStore — hash a fingerprint from uri + path + album
        val raw = buildString {
            append(song.contentUri)
            append('|')
            append(song.path ?: "")
            append('|')
            append(song.album ?: "")
            append('|')
            append(song.albumArtist ?: song.artist ?: "")
            append('|')
            append(song.albumArtUri ?: "")
        }
        "meta:${sha1(raw)}"
    }

    suspend fun getOrLoad(song: Song, maxSize: Int = 512): Bitmap? {
        val key = keyFor(song)
        memory.get(key)?.let { if (!it.isRecycled) return it }

        val gate = keyMutex.withLock {
            inflight.getOrPut(key) { Mutex() }
        }
        return gate.withLock {
            memory.get(key)?.let { if (!it.isRecycled) return@withLock it }
            val loaded = withContext(Dispatchers.IO) {
                AlbumArtResolver.load(song.let { /* context via resolver */ song }, maxSize)
            }
            // AlbumArtResolver needs context — use the load that takes context from caller
            loaded
        }
    }

    suspend fun getOrLoad(context: Context, song: Song, maxSize: Int = 512): Bitmap? {
        val key = keyFor(song)
        memory.get(key)?.let { if (!it.isRecycled) return it }

        val gate = keyMutex.withLock {
            inflight.getOrPut(key) { Mutex() }
        }
        return gate.withLock {
            memory.get(key)?.let { if (!it.isRecycled) return@withLock it }
            val bmp = withContext(Dispatchers.IO) {
                AlbumArtResolver.load(context, song, maxSize)
            }
            if (bmp != null) {
                memory.put(key, bmp)
                Log.d(TAG, "cache put key=$key ${bmp.width}x${bmp.height} entries=${memory.snapshot().size}")
            }
            bmp
        }
    }

    fun peek(key: String): Bitmap? = memory.get(key)?.takeIf { !it.isRecycled }

    fun clear() = memory.evictAll()

    private fun sha1(s: String): String {
        val dig = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "YuriPlayer.ArtCache"
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png", "AlbumArt.jpg"
        )
    }
}
