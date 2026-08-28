package capital.yuri.yuriplayer.player

import android.content.Context
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.json.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import androidx.core.net.toUri
import androidx.core.content.edit

@Serializable
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
    private val json = AppJson.json

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    var maxEntries: Int
        get() = prefs.getInt(KEY_MAX, DEFAULT_MAX).coerceIn(10, 500)
        set(value) {
            prefs.edit { putInt(KEY_MAX, value.coerceIn(10, 500)) }
            trimToMax()
            persist()
        }

    /** When true, recently played is wiped if the user closes the app from recents. */
    var clearOnClose: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_CLOSE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_CLEAR_ON_CLOSE, value) }
        }

    init {
        _entries.value = load()
    }

    /** Record a play. Consecutive duplicates of the same track are collapsed to the top. */
    fun record(song: Song) {
        val now = System.currentTimeMillis()
        val current = _entries.value.toMutableList()
        if (current.isNotEmpty() && sameSong(current.first().song, song)) {
            current[0] = HistoryEntry(song, now)
        } else {
            current.removeAll { sameSong(it.song, song) }
            current.add(0, HistoryEntry(song, now))
        }
        while (current.size > maxEntries) current.removeAt(current.lastIndex)
        _entries.value = current
        persist()
        log.d { "record '${song.displayTitle}' size=${current.size}" }
    }

    fun clear() {
        _entries.value = emptyList()
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
        log.i { "history cleared" }
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
            val dto = HistoryFileDto(
                version = VERSION,
                entries = _entries.value
            )
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(HistoryFileDto.serializer(), dto))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            log.w(e) { "persist failed" }
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val dto = json.decodeFromString(HistoryFileDto.serializer(), file.readText())
            dto.entries.take(maxEntries)
        } catch (e: Exception) {
            log.w(e) { "load failed" }
            emptyList()
        }
    }

    private fun sameSong(a: Song, b: Song): Boolean {
        if (a.path != null && b.path != null) return a.path == b.path
        return a.contentUri == b.contentUri || a.id == b.id
    }

    companion object {
        private val log = yuriLog("History")
        private const val FILE_NAME = "playback_history.json"
        private const val PREFS = "yuri_history_prefs"
        private const val KEY_MAX = "max_entries"
        private const val KEY_CLEAR_ON_CLOSE = "clear_on_close"
        private const val VERSION = 1
        const val DEFAULT_MAX = 50
        val SIZE_OPTIONS = listOf(25, 50, 100, 200, 500)
    }
}

@Serializable
private data class HistoryFileDto(
    val version: Int = 1,
    val entries: List<HistoryEntry> = emptyList()
)
