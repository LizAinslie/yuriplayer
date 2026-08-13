package capital.yuri.yuriplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun save(
        snapshot: QueueSnapshot,
        positionMs: Long,
        playWhenReady: Boolean
    ) {
        if (snapshot.hotQueue.isEmpty() && snapshot.coldQueue.isEmpty()) {
            clear()
            return
        }
        try {
            val root = JSONObject()
                .put("version", VERSION)
                .put("positionMs", positionMs.coerceAtLeast(0L))
                .put("playWhenReady", playWhenReady)
                .put("lane", snapshot.lane.name)
                .put("indexInLane", snapshot.indexInLane)
                .put("shuffleEnabled", snapshot.shuffleEnabled)
                .put("repeatMode", snapshot.repeatMode.name)
                .put("savedAt", System.currentTimeMillis())
                .put("hotQueue", songsToJson(snapshot.hotQueue))
                .put("coldQueue", songsToJson(snapshot.coldQueue))
                .put("coldOriginal", songsToJson(snapshot.coldOriginal))

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
            // Legacy v1: single "queue" array
            if (root.has("queue") && !root.has("coldQueue")) {
                return loadLegacy(root)
            }

            val hot = jsonToSongs(root.optJSONArray("hotQueue"))
            val cold = jsonToSongs(root.optJSONArray("coldQueue"))
            val coldOriginal = jsonToSongs(root.optJSONArray("coldOriginal")).ifEmpty { cold }
            if (hot.isEmpty() && cold.isEmpty()) return null

            val lane = try {
                QueueLane.valueOf(root.optString("lane", QueueLane.COLD.name))
            } catch (_: Exception) {
                QueueLane.COLD
            }
            val repeat = try {
                RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name))
            } catch (_: Exception) {
                RepeatMode.OFF
            }

            SavedPlayback(
                snapshot = QueueSnapshot(
                    hotQueue = hot,
                    coldQueue = cold,
                    coldOriginal = coldOriginal,
                    lane = lane,
                    indexInLane = root.optInt("indexInLane", 0),
                    shuffleEnabled = root.optBoolean("shuffleEnabled", false),
                    repeatMode = repeat
                ),
                positionMs = root.optLong("positionMs", 0L),
                playWhenReady = root.optBoolean("playWhenReady", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load playback state", e)
            null
        }
    }

    private fun loadLegacy(root: JSONObject): SavedPlayback? {
        val queue = jsonToSongs(root.optJSONArray("queue"))
        if (queue.isEmpty()) return null
        val index = root.optInt("index", 0).coerceIn(0, queue.lastIndex)
        return SavedPlayback(
            snapshot = QueueSnapshot(
                hotQueue = emptyList(),
                coldQueue = queue,
                coldOriginal = queue,
                lane = QueueLane.COLD,
                indexInLane = index,
                shuffleEnabled = false,
                repeatMode = RepeatMode.OFF
            ),
            positionMs = root.optLong("positionMs", 0L),
            playWhenReady = root.optBoolean("playWhenReady", false)
        )
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

    private fun songsToJson(songs: List<Song>): JSONArray {
        val arr = JSONArray()
        songs.forEach { arr.put(songToJson(it)) }
        return arr
    }

    private fun jsonToSongs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val list = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            list += songFromJson(arr.getJSONObject(i))
        }
        return list
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
        val snapshot: QueueSnapshot,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val FILE_NAME = "playback_state.json"
        private const val VERSION = 2
    }
}
