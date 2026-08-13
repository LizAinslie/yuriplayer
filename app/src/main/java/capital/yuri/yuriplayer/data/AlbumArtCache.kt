package capital.yuri.yuriplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tiny in-memory cover cache for now-playing / peek transitions.
 * Hard cap of [MAX_ENTRIES] bitmaps so we never balloon RAM.
 * Identical art (same folder cover or same album identity) shares one slot.
 */
class AlbumArtCache {

    private val lock = Mutex()
    /** Access-ordered: eldest evicted when over capacity. */
    private val map = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val drop = size > MAX_ENTRIES
            if (drop) Log.d(TAG, "evict ${eldest?.key}")
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

    suspend fun get(context: Context, song: Song, maxSize: Int = 512): Bitmap? {
        val key = artKey(song)
        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val bmp = withContext(Dispatchers.IO) {
            AlbumArtResolver.loadUncached(context, song, maxSize)
        } ?: return null
        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
            map[key] = bmp
            Log.d(TAG, "put $key size=${map.size}")
        }
        return bmp
    }

    /** Warm cache for current + neighbors (deduped by art key, still ≤ 4 slots). */
    suspend fun prefetch(context: Context, songs: List<Song?>, maxSize: Int = 512) {
        val seen = mutableSetOf<String>()
        for (song in songs) {
            if (song == null) continue
            val key = artKey(song)
            if (!seen.add(key)) continue
            get(context, song, maxSize)
        }
    }

    suspend fun clear() = lock.withLock { map.clear() }

    companion object {
        private const val TAG = "YuriPlayer.ArtCache"
        const val MAX_ENTRIES = 4
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png", "AlbumArt.jpg"
        )
    }
}
