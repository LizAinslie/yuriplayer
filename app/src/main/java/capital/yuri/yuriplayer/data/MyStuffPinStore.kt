package capital.yuri.yuriplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class StuffPinKind {
    ALBUM,
    ARTIST,
    PLAYLIST,
    SONG
}

@Serializable
data class StuffPin(
    val kind: StuffPinKind,
    /** albumKey / artistKey / playlist id / songKey */
    val id: String,
    val title: String,
    val subtitle: String = ""
)

/**
 * User pins on the My Stuff home grid (artists, playlists, songs, albums).
 * Ordered list; persisted as JSON in SharedPreferences.
 */
class MyStuffPinStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<List<StuffPin>> = _pins.asStateFlow()

    fun contains(kind: StuffPinKind, id: String): Boolean =
        _pins.value.any { it.kind == kind && it.id == id }

    fun toggle(pin: StuffPin): Boolean {
        return if (contains(pin.kind, pin.id)) {
            remove(pin)
            false
        } else {
            add(pin)
            true
        }
    }

    fun add(pin: StuffPin) {
        val cur = _pins.value.toMutableList()
        if (cur.any { it.kind == pin.kind && it.id == pin.id }) return
        cur.add(pin)
        persist(cur)
    }

    fun remove(pin: StuffPin) {
        val next = _pins.value.filterNot { it.kind == pin.kind && it.id == pin.id }
        persist(next)
    }

    fun removeAt(index: Int) {
        val cur = _pins.value.toMutableList()
        if (index !in cur.indices) return
        cur.removeAt(index)
        persist(cur)
    }

    fun move(from: Int, to: Int) {
        val cur = _pins.value.toMutableList()
        if (from !in cur.indices || to !in cur.indices || from == to) return
        val item = cur.removeAt(from)
        cur.add(to, item)
        persist(cur)
    }

    private fun persist(list: List<StuffPin>) {
        _pins.value = list
        prefs.edit().putString(KEY_PINS, json.encodeToString(list)).apply()
    }

    private fun load(): List<StuffPin> {
        val raw = prefs.getString(KEY_PINS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StuffPin>>(raw) }.getOrElse { emptyList() }
    }

    companion object {
        private const val PREFS = "my_stuff_pins"
        private const val KEY_PINS = "pins"
        /** Total grid cells target (fixed browse cards + pins + empties). */
        const val GRID_MIN_CELLS = 8
        const val FIXED_BROWSE_COUNT = 3
    }
}
