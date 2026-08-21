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
 * Cover cache tuned for list scrolling on mid-range devices, with a tiny HQ
 * LRU for now-playing (current + next + prev) so the full-player art is sharp.
 *
 * When [AlbumCoverPrefs] has a preferred URI for the albumKey, that wins the
 * cache key and decode path so list + hero stay in sync after a user swap.
 *
 * Tiers never upscale: a 512px hero miss must re-decode from source for HQ.
 */
class AlbumArtCache(
    context: Context,
    private val coverPrefs: AlbumCoverPrefs
) {
    private val appContext = context.applicationContext
    private val diskDir = File(appContext.cacheDir, "album_art").also { it.mkdirs() }
    private val enrichedDir = File(appContext.filesDir, "covers")
    private val lock = Mutex()

    private val thumbs = object : LinkedHashMap<String, Bitmap>(MAX_THUMB_MEMORY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val drop = size > MAX_THUMB_MEMORY
            if (drop) Log.d(TAG, "thumb-evict ${eldest?.key}")
            return drop
        }
    }

    private val heroes = object : LinkedHashMap<String, Bitmap>(MAX_HERO_MEMORY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val drop = size > MAX_HERO_MEMORY
            if (drop) Log.d(TAG, "hero-evict ${eldest?.key}")
            return drop
        }
    }

    private val hq = object : LinkedHashMap<String, Bitmap>(MAX_HQ_MEMORY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val drop = size > MAX_HQ_MEMORY
            if (drop) Log.d(TAG, "hq-evict ${eldest?.key}")
            return drop
        }
    }

    fun artKey(song: Song): String {
        val aKey = albumKey(song.album, song.effectiveAlbumArtist)
        val preferred = coverPrefs.preferredUri(aKey)
        if (!preferred.isNullOrBlank()) {
            return "preferred:$preferred"
        }
        val path = song.path
        // Virtual library paths (jellyfin:uuid) are not filesystem folders.
        if (path != null && looksLikeFsPath(path)) {
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
        // Remote tracks: the item image URL is the real cover. Keying by
        // album name alone reused Vessel art for later TOP singles.
        val artUri = song.albumArtUri?.toString()?.takeIf { it.isNotBlank() }
        if (artUri != null) {
            return "uri:$artUri"
        }
        val enriched = enrichedCoverFile(aKey)
        if (enriched.isFile && enriched.length() > 0) {
            return "enriched:${enriched.absolutePath}:${enriched.length()}:${enriched.lastModified()}"
        }
        val album = song.album?.trim()?.lowercase().orEmpty()
        val artist = (song.albumArtist ?: song.artist)?.trim()?.lowercase().orEmpty()
        if (album.isNotEmpty()) return "album:$album|$artist"
        return "song:${song.path ?: song.contentUri}"
    }

    private fun looksLikeFsPath(path: String): Boolean {
        if (path.contains("://")) return false
        if (path.startsWith("jellyfin:") ||
            path.startsWith("subsonic:") ||
            path.startsWith("navidrome:") ||
            path.startsWith("webdav:")
        ) {
            return false
        }
        return path.startsWith("/")
    }

    fun enrichedCoverFile(albumKey: String): File {
        val name = MetadataEnrichmentService.sanitizeFileName(albumKey) + ".jpg"
        return File(enrichedDir, name)
    }

    fun tierSize(maxSize: Int): Int = when {
        maxSize <= THUMB_DECODE_SIZE -> THUMB_DECODE_SIZE
        maxSize <= 256 -> 256
        maxSize <= HERO_DECODE_SIZE -> HERO_DECODE_SIZE
        else -> HQ_DECODE_SIZE
    }

    private fun isThumbTier(tier: Int): Boolean = tier <= THUMB_DECODE_SIZE

    private fun isHqTier(tier: Int): Boolean = tier >= HQ_DECODE_SIZE

    private fun memKey(baseKey: String, tier: Int): String = "$baseKey@$tier"

    private fun mapFor(tier: Int): LinkedHashMap<String, Bitmap> = when {
        isThumbTier(tier) -> thumbs
        isHqTier(tier) -> hq
        else -> heroes
    }

    fun peek(song: Song, maxSize: Int = THUMB_DECODE_SIZE): Bitmap? {
        val tier = tierSize(maxSize)
        val key = memKey(artKey(song), tier)
        val bmp = mapFor(tier)[key]
        return bmp?.takeIf { !it.isRecycled }
    }

    fun peekKey(baseKey: String, maxSize: Int = THUMB_DECODE_SIZE): Bitmap? {
        val tier = tierSize(maxSize)
        val key = memKey(baseKey, tier)
        val bmp = mapFor(tier)[key]
        return bmp?.takeIf { !it.isRecycled }
    }

    suspend fun invalidateAlbum(albumKeyStr: String) {
        val enriched = enrichedCoverFile(albumKeyStr)
        val preferred = coverPrefs.preferredUri(albumKeyStr)
        val markers = listOfNotNull(
            albumKeyStr.lowercase(),
            enriched.absolutePath,
            preferred,
            "album:" + albumKeyStr.substringBefore('|').lowercase(),
            preferred?.let { "preferred:$it" }
        )
        lock.withLock {
            fun purge(map: LinkedHashMap<String, Bitmap>) {
                val keys = map.keys.filter { k ->
                    markers.any { m -> k.contains(m, ignoreCase = true) }
                }
                keys.forEach { map.remove(it) }
                if (keys.isNotEmpty()) Log.d(TAG, "invalidate album=$albumKeyStr keys=$keys")
            }
            purge(thumbs)
            purge(heroes)
            purge(hq)
        }
    }

    suspend fun invalidateAllMemory() = lock.withLock {
        thumbs.clear()
        heroes.clear()
        hq.clear()
        Log.d(TAG, "mem-clear")
    }

    private fun diskFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
            .take(40)
        return File(diskDir, "$digest.jpg")
    }

    private fun readDisk(key: String, minDim: Int = 0): Bitmap? {
        val f = diskFile(key)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)?.takeIf { !it.isRecycled }
            if (bmp == null) return null
            if (minDim > 0 && maxOf(bmp.width, bmp.height) < minDim) {
                Log.d(TAG, "disk-stale $key ${bmp.width}x${bmp.height} < $minDim")
                f.delete()
                null
            } else {
                bmp
            }
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
                val quality = if (key.endsWith("@$HQ_DECODE_SIZE")) 92 else 85
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
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

    private fun trimDiskIfNeeded() {
        val files = diskDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") && !f.name.startsWith("tmp-") }
            ?: return
        if (files.size <= MAX_DISK) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK)
            .forEach { it.delete() }
    }

    suspend fun get(context: Context, song: Song, maxSize: Int = HERO_DECODE_SIZE): Bitmap? {
        val tier = tierSize(maxSize)
        val base = artKey(song)
        val key = memKey(base, tier)
        val map = mapFor(tier)

        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
        }

        // Drop list-thumb JPEGs that were previously written into a hero/HQ slot.
        val minDiskDim = when {
            isHqTier(tier) -> 400
            !isThumbTier(tier) -> 200
            else -> 0
        }
        val fromDisk = withContext(Dispatchers.IO) { readDisk(key, minDim = minDiskDim) }
        if (fromDisk != null) {
            lock.withLock {
                map[key]?.takeIf { !it.isRecycled }?.let { return it }
                map[key] = fromDisk
                Log.d(TAG, "disk-hit $key mem=${map.size}")
            }
            return fromDisk
        }

        // Downscale from a higher in-memory tier. Never upscale 512 → 1024.
        if (tier < HQ_DECODE_SIZE) {
            val hqKey = memKey(base, HQ_DECODE_SIZE)
            val hqBmp = lock.withLock { hq[hqKey]?.takeIf { !it.isRecycled } }
            if (hqBmp != null) {
                val scaled = withContext(Dispatchers.Default) { scaleTo(hqBmp, tier) }
                lock.withLock {
                    map[key]?.takeIf { !it.isRecycled }?.let { return it }
                    map[key] = scaled
                }
                withContext(Dispatchers.IO) { writeDisk(key, scaled) }
                return scaled
            }
        }

        if (isThumbTier(tier)) {
            val heroKey = memKey(base, HERO_DECODE_SIZE)
            val heroBmp = lock.withLock { heroes[heroKey]?.takeIf { !it.isRecycled } }
            if (heroBmp != null) {
                val scaled = withContext(Dispatchers.Default) {
                    scaleTo(heroBmp, tier)
                }
                lock.withLock {
                    map[key]?.takeIf { !it.isRecycled }?.let { return it }
                    map[key] = scaled
                }
                withContext(Dispatchers.IO) { writeDisk(key, scaled) }
                return scaled
            }
        }

        val aKey = albumKey(song.album, song.effectiveAlbumArtist)
        val preferred = coverPrefs.preferredUri(aKey)
        val bmp = withContext(Dispatchers.IO) {
            AlbumArtResolver.loadUncached(context, song, tier, preferredUri = preferred)
        } ?: return null

        withContext(Dispatchers.IO) { writeDisk(key, bmp) }

        lock.withLock {
            map[key]?.takeIf { !it.isRecycled }?.let { return it }
            map[key] = bmp
            Log.d(TAG, "decode-put $key mem=${map.size}")
        }
        return bmp
    }

    private fun scaleTo(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSize && h <= maxSize) return src
        val scale = maxSize.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    suspend fun prefetch(context: Context, songs: List<Song?>, maxSize: Int = THUMB_DECODE_SIZE) {
        val seen = mutableSetOf<String>()
        val tier = tierSize(maxSize)
        for (song in songs) {
            if (song == null) continue
            val key = memKey(artKey(song), tier)
            if (!seen.add(key)) continue
            try {
                get(context, song, tier)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed $key", e)
            }
        }
    }

    suspend fun clearMemory() = lock.withLock {
        thumbs.clear()
        heroes.clear()
        hq.clear()
    }

    suspend fun clearAll() {
        lock.withLock {
            thumbs.clear()
            heroes.clear()
            hq.clear()
        }
        withContext(Dispatchers.IO) {
            diskDir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        private const val TAG = "YuriPlayer.ArtCache"
        const val THUMB_DECODE_SIZE = 128
        const val HERO_DECODE_SIZE = 512
        /** Now-playing cover. Stylo 4 NP art is ~680px; 512 looked soft. */
        const val HQ_DECODE_SIZE = 1024
        const val MAX_THUMB_MEMORY = 96
        const val MAX_HERO_MEMORY = 6
        /** Current + next + prev for swipe. ~4MB each ARGB. */
        const val MAX_HQ_MEMORY = 3
        const val MAX_MEMORY = MAX_THUMB_MEMORY
        const val MAX_DISK = 128

        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png", "AlbumArt.jpg"
        )
    }
}
