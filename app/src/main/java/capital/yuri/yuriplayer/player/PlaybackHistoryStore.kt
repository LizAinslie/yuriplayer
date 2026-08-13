package capital.yuri.yuriplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val song: Song,
    val playedAtMs: Long
)

/**
 * Recently-played list, separate from the active queue.
 * Survives process death via filesDir JSON. Newest first.
 */
class PlaybackHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file = File(context.filesDir, FILE_NAME)

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    var maxEntries: Int
        get() = prefs.getInt(KEY_MAX, DEFAULT_MAX).coerceIn(10, 500)
        set(value) {
            prefs.edit().putInt(KEY_MAX, value.coerceIn(10, 500)).apply()
            trimToMax()
            persist()
        }

    init {
        _entries.value = load()
    }

    /** Record a play. Consecutive duplicates of the same track are collapsed to the top. */
    fun record(song: Song) {
        val now = System.currentTimeMillis()
        val current = _entries.value.toMutableList()
        // Drop adjacent duplicate (same path/uri)
        if (current.isNotEmpty() && sameSong(current.first().song, song)) {
            current[0] = HistoryEntry(song, now)
        } else {
            // Remove older copies of same song so it only appears once, at top
            current.removeAll { sameSong(it.song, song) }
            current.add(0, HistoryEntry(song, now))
        }
        while (current.size > maxEntries) current.removeAt(current.lastIndex)
        _entries.value = current
        persist()
        Log.d(TAG, "record '${song.displayTitle}' size=${current.size}")
    }

    fun clear() {
        _entries.value = emptyList()
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
        Log.i(TAG, "history cleared")
    }

    private fun trimToMax() {
        val max = maxEntries
        val list = _entries.value
        if (list.size > max) {
            _entries.value = list.take(max)
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            _entries.value.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("playedAtMs", e.playedAtMs)
                        .put("song", songToJson(e.song))
                )
            }
            val root = JSONObject()
                .put("version", VERSION)
                .put("entries", arr)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("entries") ?: return emptyList()
            val list = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list += HistoryEntry(
                    song = songFromJson(obj.getJSONObject("song")),
                    playedAtMs = obj.optLong("playedAtMs", 0L)
                )
            }
            list.take(maxEntries)
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
            emptyList()
        }
    }

    private fun sameSong(a: Song, b: Song): Boolean {
        if (a.path != null && b.path != null) return a.path == b.path
        return a.contentUri == b.contentUri || a.id == b.id
    }

    private fun songToJson(song: Song): JSONObject =
        JSONObject()
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

    companion object {
        private const val TAG = "YuriPlayer.History"
        private const val FILE_NAME = "playback_history.json"
        private const val PREFS = "yuri_history_prefs"
        private const val KEY_MAX = "max_entries"
        private const val VERSION = 1
        const val DEFAULT_MAX = 50
    }
}
