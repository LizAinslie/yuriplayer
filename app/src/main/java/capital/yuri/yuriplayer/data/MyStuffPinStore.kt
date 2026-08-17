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
) {
    val key: String get() = "${kind.name}:$id"
}

/**
 * My Stuff has two layers:
 * - [entries]: everything the user saved via heart / "Add to My Stuff"
 * - [pins]: ordered subset shown on the home grid (max [PIN_SLOTS])
 *
 * Adding to My Stuff does NOT auto-pin. Pins are chosen from entries only.
 */
class MyStuffPinStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<StuffPin>> = _entries.asStateFlow()

    /** Ordered pin keys (kind:id), max [PIN_SLOTS]. Only keys present in entries. */
    private val _pinKeys = MutableStateFlow(loadPinKeys())
    val pinKeys: StateFlow<List<String>> = _pinKeys.asStateFlow()

    /** Resolved pin entries in display order. */
    val pins: StateFlow<List<StuffPin>>
        get() = _pinsResolved

    private val _pinsResolved = MutableStateFlow(resolvePins())

    // ---- collection (Add to My Stuff) ----

    fun contains(kind: StuffPinKind, id: String): Boolean =
        _entries.value.any { it.kind == kind && it.id == id }

    fun contains(pin: StuffPin): Boolean = contains(pin.kind, pin.id)

    fun addEntry(pin: StuffPin) {
        val cur = _entries.value.toMutableList()
        if (cur.any { it.kind == pin.kind && it.id == pin.id }) return
        cur.add(pin)
        persistEntries(cur)
    }

    fun removeEntry(pin: StuffPin) {
        val next = _entries.value.filterNot { it.kind == pin.kind && it.id == pin.id }
        persistEntries(next)
        // Drop pin if it was pinned
        val keys = _pinKeys.value.filterNot { it == pin.key }
        if (keys.size != _pinKeys.value.size) persistPinKeys(keys)
    }

    /** @return true if now in collection */
    fun toggleEntry(pin: StuffPin): Boolean {
        return if (contains(pin)) {
            removeEntry(pin)
            false
        } else {
            addEntry(pin)
            true
        }
    }

    // ---- pins (home slots) ----

    fun isPinned(kind: StuffPinKind, id: String): Boolean =
        _pinKeys.value.contains("${kind.name}:$id")

    fun isPinned(pin: StuffPin): Boolean = isPinned(pin.kind, pin.id)

    /** Pin an entry already in the collection. No-op if full or not in collection. */
    fun pin(pin: StuffPin) {
        if (!contains(pin)) return
        val keys = _pinKeys.value.toMutableList()
        if (pin.key in keys) return
        if (keys.size >= PIN_SLOTS) return
        keys.add(pin.key)
        persistPinKeys(keys)
    }

    fun unpin(pin: StuffPin) {
        val keys = _pinKeys.value.filterNot { it == pin.key }
        persistPinKeys(keys)
    }

    fun unpinAt(index: Int) {
        val keys = _pinKeys.value.toMutableList()
        if (index !in keys.indices) return
        keys.removeAt(index)
        persistPinKeys(keys)
    }

    fun movePin(from: Int, to: Int) {
        val keys = _pinKeys.value.toMutableList()
        if (from !in keys.indices || to !in keys.indices || from == to) return
        val item = keys.removeAt(from)
        keys.add(to, item)
        persistPinKeys(keys)
    }

    // ---- legacy aliases (keep call sites compiling during transition) ----

    @Deprecated("Use addEntry — does not pin", ReplaceWith("addEntry(pin)"))
    fun add(pin: StuffPin) = addEntry(pin)

    @Deprecated("Use removeEntry", ReplaceWith("removeEntry(pin)"))
    fun remove(pin: StuffPin) = removeEntry(pin)

    fun toggle(pin: StuffPin): Boolean = toggleEntry(pin)

    // ---- persist ----

    private fun resolvePins(): List<StuffPin> {
        val byKey = _entries.value.associateBy { it.key }
        return _pinKeys.value.mapNotNull { byKey[it] }
    }

    private fun persistEntries(list: List<StuffPin>) {
        _entries.value = list
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(list)).apply()
        // Re-resolve pins in case an entry was removed
        val validKeys = list.map { it.key }.toSet()
        val cleaned = _pinKeys.value.filter { it in validKeys }
        if (cleaned != _pinKeys.value) {
            _pinKeys.value = cleaned
            prefs.edit().putString(KEY_PIN_KEYS, json.encodeToString(cleaned)).apply()
        }
        _pinsResolved.value = resolvePins()
    }

    private fun persistPinKeys(keys: List<String>) {
        val valid = _entries.value.map { it.key }.toSet()
        val cleaned = keys.filter { it in valid }.take(PIN_SLOTS)
        _pinKeys.value = cleaned
        prefs.edit().putString(KEY_PIN_KEYS, json.encodeToString(cleaned)).apply()
        _pinsResolved.value = resolvePins()
    }

    private fun loadEntries(): List<StuffPin> {
        // Prefer new key; migrate old "pins" blob into entries once
        val raw = prefs.getString(KEY_ENTRIES, null)
            ?: prefs.getString(KEY_LEGACY_PINS, null)
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<StuffPin>>(raw) }.getOrElse { emptyList() }
    }

    private fun loadPinKeys(): List<String> {
        val raw = prefs.getString(KEY_PIN_KEYS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
    }

    companion object {
        private const val PREFS = "my_stuff_pins"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_PIN_KEYS = "pin_keys"
        private const val KEY_LEGACY_PINS = "pins"

        /** Total home pin slots (filled + empty). */
        const val PIN_SLOTS = 10

        @Deprecated("Use PIN_SLOTS")
        const val GRID_MIN_CELLS = 10
        @Deprecated("No fixed browse cards")
        const val FIXED_BROWSE_COUNT = 0
    }
}
