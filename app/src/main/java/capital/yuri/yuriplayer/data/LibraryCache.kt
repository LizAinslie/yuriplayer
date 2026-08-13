package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Long-lived on-disk cache of the scanned library.
 * Sort/search read from memory; disk is only rewritten after a full scan.
 */
class LibraryCache(context: Context) {

    private val file = File(context.filesDir, "library_index.json")

    fun load(): CachedLibrary? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val scannedAt = root.optLong("scannedAt", 0L)
            val arr = root.getJSONArray("songs")
            val songs = buildList {
                for (i in 0 until arr.length()) {
                    add(songFromJson(arr.getJSONObject(i)))
                }
            }
            CachedLibrary(songs, scannedAt)
        } catch (_: Exception) {
            null
        }
    }

    fun save(songs: List<Song>) {
        val arr = JSONArray()
        songs.forEach { arr.put(songToJson(it)) }
        val root = JSONObject()
            .put("scannedAt", System.currentTimeMillis())
            .put("songs", arr)
        file.writeText(root.toString())
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun songToJson(song: Song): JSONObject {
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put("artist", song.artist)
            .put("album", song.album)
            .put("durationMs", song.durationMs)
            .put("contentUri", song.contentUri.toString())
            .put("albumArtUri", song.albumArtUri?.toString())
            .put("trackNumber", song.trackNumber)
            .put("year", song.year)
            .put("path", song.path)
            .put("mimeType", song.mimeType)
    }

    private fun songFromJson(obj: JSONObject): Song {
        val art = obj.optString("albumArtUri", null)
        return Song(
            id = obj.getLong("id"),
            title = obj.getString("title"),
            artist = obj.getString("artist"),
            album = obj.getString("album"),
            durationMs = obj.getLong("durationMs"),
            contentUri = Uri.parse(obj.getString("contentUri")),
            albumArtUri = art?.takeIf { it.isNotBlank() && it != "null" }?.let { Uri.parse(it) },
            trackNumber = obj.optInt("trackNumber", 0),
            year = obj.optInt("year", 0),
            path = obj.optString("path", null)?.takeIf { it != "null" },
            mimeType = obj.optString("mimeType", null)?.takeIf { it != "null" }
        )
    }
}

data class CachedLibrary(
    val songs: List<Song>,
    val scannedAt: Long
)
