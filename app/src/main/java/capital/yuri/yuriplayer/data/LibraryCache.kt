package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
            tmpFile.writeText(root.toString())
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save library cache", e)
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
            if (tmpFile.exists()) tmpFile.delete()
        } catch (_: Exception) {}
    }

    fun cacheFilePath(): String = file.absolutePath

    private fun songToJson(song: Song): JSONObject {
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title ?: JSONObject.NULL)
            .put("artist", song.artist ?: JSONObject.NULL)
            .put("albumArtist", song.albumArtist ?: JSONObject.NULL)
            .put("album", song.album ?: JSONObject.NULL)
            .put("durationMs", song.durationMs ?: JSONObject.NULL)
            .put("contentUri", song.contentUri.toString())
            .put("albumArtUri", song.albumArtUri?.toString() ?: JSONObject.NULL)
            .put("trackNumber", song.trackNumber ?: JSONObject.NULL)
            .put("year", song.year ?: JSONObject.NULL)
            .put("path", song.path ?: JSONObject.NULL)
            .put("mimeType", song.mimeType ?: JSONObject.NULL)
    }

    private fun songFromJson(obj: JSONObject): Song {
        fun optStr(key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, null)?.takeIf { it.isNotBlank() && it != "null" }
        }
        fun optLong(key: String): Long? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optLong(key)
        }
        fun optInt(key: String): Int? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optInt(key)
        }
        val art = optStr("albumArtUri")
        // Migrate old caches that stored "Unknown Artist" etc.
        fun clean(s: String?): String? = s?.takeIf {
            it.isNotBlank() &&
                !it.equals("<unknown>", true) &&
                !it.equals("Unknown Artist", true) &&
                !it.equals("Unknown Album", true)
        }
        return Song(
            id = obj.getLong("id"),
            title = clean(optStr("title")),
            artist = clean(optStr("artist")),
            albumArtist = clean(optStr("albumArtist")),
            album = clean(optStr("album")),
            durationMs = optLong("durationMs"),
            contentUri = Uri.parse(obj.getString("contentUri")),
            albumArtUri = art?.let { Uri.parse(it) },
            trackNumber = optInt("trackNumber")?.takeIf { it > 0 },
            year = optInt("year")?.takeIf { it > 0 },
            path = optStr("path"),
            mimeType = optStr("mimeType")
        )
    }

    companion object {
        private const val TAG = "LibraryCache"
        private const val FILE_NAME = "library_index.json"
        private const val CACHE_VERSION = 3
    }
}

data class CachedLibrary(
    val songs: List<Song>,
    val scannedAt: Long
)
