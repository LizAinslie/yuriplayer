package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk library index in the app's [Context.getCacheDir].
 *
 * Flow:
 * - Full MediaStore/filesystem scan → [save]
 * - App start → [load] into memory ([LibraryIndex])
 * - Sort / search / albums / artists only use the in-memory list
 *
 * Android may clear cacheDir under storage pressure; the next [LibraryIndex.refresh]
 * rebuilds it. For a more permanent store we can move to filesDir later.
 */
class LibraryCache(context: Context) {

    private val cacheDir = context.cacheDir
    private val file = File(cacheDir, FILE_NAME)
    private val tmpFile = File(cacheDir, "$FILE_NAME.tmp")

    fun load(): CachedLibrary? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val root = JSONObject(file.readText())
            val scannedAt = root.optLong("scannedAt", 0L)
            val arr = root.getJSONArray("songs")
            val songs = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                songs += songFromJson(arr.getJSONObject(i))
            }
            Log.d(TAG, "Loaded ${songs.size} songs from cache (scannedAt=$scannedAt)")
            CachedLibrary(songs, scannedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load library cache", e)
            null
        }
    }

    fun save(songs: List<Song>) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val arr = JSONArray()
            songs.forEach { arr.put(songToJson(it)) }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("scannedAt", System.currentTimeMillis())
                .put("songs", arr)

            // Atomic-ish write: temp file then rename over the real one
            tmpFile.writeText(root.toString())
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            Log.d(TAG, "Saved ${songs.size} songs to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save library cache", e)
            try {
                tmpFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
            if (tmpFile.exists()) tmpFile.delete()
        } catch (_: Exception) {
        }
    }

    fun cacheFilePath(): String = file.absolutePath

    private fun songToJson(song: Song): JSONObject {
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put("artist", song.artist)
            .put("album", song.album)
            .put("durationMs", song.durationMs)
            .put("contentUri", song.contentUri.toString())
            .put("albumArtUri", song.albumArtUri?.toString() ?: JSONObject.NULL)
            .put("trackNumber", song.trackNumber)
            .put("year", song.year)
            .put("path", song.path ?: JSONObject.NULL)
            .put("mimeType", song.mimeType ?: JSONObject.NULL)
    }

    private fun songFromJson(obj: JSONObject): Song {
        fun optNullableString(key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optString(key, "")
            return v.takeIf { it.isNotBlank() && it != "null" }
        }

        val art = optNullableString("albumArtUri")
        return Song(
            id = obj.getLong("id"),
            title = obj.getString("title"),
            artist = obj.getString("artist"),
            album = obj.getString("album"),
            durationMs = obj.getLong("durationMs"),
            contentUri = Uri.parse(obj.getString("contentUri")),
            albumArtUri = art?.let { Uri.parse(it) },
            trackNumber = obj.optInt("trackNumber", 0),
            year = obj.optInt("year", 0),
            path = optNullableString("path"),
            mimeType = optNullableString("mimeType")
        )
    }

    companion object {
        private const val TAG = "LibraryCache"
        private const val FILE_NAME = "library_index.json"
        private const val CACHE_VERSION = 1
    }
}

data class CachedLibrary(
    val songs: List<Song>,
    val scannedAt: Long
)
