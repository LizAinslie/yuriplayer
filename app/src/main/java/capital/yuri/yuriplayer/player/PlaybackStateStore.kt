package capital.yuri.yuriplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the current queue + position so playback can resume after process death.
 * Stored under filesDir (not cache) so it is not wiped with the library cache.
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun save(
        queue: List<Song>,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean
    ) {
        if (queue.isEmpty() || index < 0) {
            clear()
            return
        }
        try {
            val arr = JSONArray()
            queue.forEach { arr.put(songToJson(it)) }
            val root = JSONObject()
                .put("version", VERSION)
                .put("index", index)
                .put("positionMs", positionMs.coerceAtLeast(0L))
                .put("playWhenReady", playWhenReady)
                .put("savedAt", System.currentTimeMillis())
                .put("queue", arr)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state", e)
        }
    }

    fun load(): SavedPlayback? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("queue")
            val queue = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                queue += songFromJson(arr.getJSONObject(i))
            }
            if (queue.isEmpty()) return null
            SavedPlayback(
                queue = queue,
                index = root.optInt("index", 0).coerceIn(0, queue.lastIndex),
                positionMs = root.optLong("positionMs", 0L),
                playWhenReady = root.optBoolean("playWhenReady", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load playback state", e)
            null
        }
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

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
        return Song(
            id = obj.getLong("id"),
            title = optStr("title"),
            artist = optStr("artist"),
            albumArtist = optStr("albumArtist"),
            album = optStr("album"),
            durationMs = optLong("durationMs"),
            contentUri = Uri.parse(obj.getString("contentUri")),
            albumArtUri = art?.let { Uri.parse(it) },
            trackNumber = optInt("trackNumber"),
            year = optInt("year"),
            path = optStr("path"),
            mimeType = optStr("mimeType")
        )
    }

    data class SavedPlayback(
        val queue: List<Song>,
        val index: Int,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val FILE_NAME = "playback_state.json"
        private const val VERSION = 1
    }
}
