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
 * Adding an album also saves every track on that album (cascade).
 * Unhearting an album removes only cascade-added tracks — songs the user
 * hearted on their own stay in the collection.
 */
class MyStuffPinStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<StuffPin>> = _entries.asStateFlow()

    private val _pinKeys = MutableStateFlow(loadPinKeys())
    val pinKeys: StateFlow<List<String>> = _pinKeys.asStateFlow()

    val pins: StateFlow<List<StuffPin>>
        get() = _pinsResolved

    private val _pinsResolved = MutableStateFlow(resolvePins())

    /** albumKey → songKeys auto-added with that album (not independently hearted). */
    private val cascadeByAlbum = loadCascade().toMutableMap()

    fun contains(kind: StuffPinKind, id: String): Boolean =
        _entries.value.any { it.kind == kind && it.id == id }

    fun contains(pin: StuffPin): Boolean = contains(pin.kind, pin.id)

    fun addEntry(pin: StuffPin) {
        val cur = _entries.value.toMutableList()
        if (cur.any { it.kind == pin.kind && it.id == pin.id }) return
        cur.add(pin)
        // Independent song heart: drop from any cascade sets
        if (pin.kind == StuffPinKind.SONG) {
            cascadeByAlbum.keys.toList().forEach { ak ->
                val set = cascadeByAlbum[ak] ?: return@forEach
                if (pin.id in set) {
                    cascadeByAlbum[ak] = set - pin.id
                }
            }
            persistCascade()
        }
        persistEntries(cur)
    }

    fun addEntries(pins: List<StuffPin>) {
        if (pins.isEmpty()) return
        val cur = _entries.value.toMutableList()
        var changed = false
        pins.forEach { pin ->
            if (cur.none { it.kind == pin.kind && it.id == pin.id }) {
                cur.add(pin)
                changed = true
            }
        }
        if (changed) persistEntries(cur)
    }

    /** Album entry + tracks not already in collection (those become cascade). */
    fun addAlbumWithSongs(album: AlbumItem) {
        val aKey = albumKey(album.name, album.artist)
        val existingSongIds = _entries.value
            .filter { it.kind == StuffPinKind.SONG }
            .map { it.id }
            .toSet()

        val cascadeKeys = mutableSetOf<String>()
        val batch = buildList {
            add(
                StuffPin(
                    kind = StuffPinKind.ALBUM,
                    id = aKey,
                    title = album.displayName,
                    subtitle = album.displayArtist
                )
            )
            album.songs.forEach { song ->
                val sk = song.songKey
                if (sk !in existingSongIds) {
                    cascadeKeys += sk
                    add(
                        StuffPin(
                            kind = StuffPinKind.SONG,
                            id = sk,
                            title = song.displayTitle,
                            subtitle = song.displayArtist
                        )
                    )
                }
                // already present → independent, leave alone
            }
        }
        addEntries(batch)
        if (cascadeKeys.isNotEmpty()) {
            cascadeByAlbum[aKey] = (cascadeByAlbum[aKey] ?: emptySet()) + cascadeKeys
            persistCascade()
        }
    }

    fun removeEntry(pin: StuffPin) {
        val next = _entries.value.filterNot { it.kind == pin.kind && it.id == pin.id }
        persistEntries(next)
        val keys = _pinKeys.value.filterNot { it == pin.key }
        if (keys.size != _pinKeys.value.size) persistPinKeys(keys)
        if (pin.kind == StuffPinKind.SONG) {
            cascadeByAlbum.keys.toList().forEach { ak ->
                val set = cascadeByAlbum[ak] ?: return@forEach
                if (pin.id in set) cascadeByAlbum[ak] = set - pin.id
            }
            persistCascade()
        }
    }

    /**
     * Heart album → album + missing songs (cascade).
     * Unheart → drop album + cascade-only songs; independently hearted songs stay.
     */
    fun toggleAlbum(album: AlbumItem): Boolean {
        val aKey = albumKey(album.name, album.artist)
        val pin = StuffPin(
            kind = StuffPinKind.ALBUM,
            id = aKey,
            title = album.displayName,
            subtitle = album.displayArtist
        )
        return if (contains(pin)) {
            removeAlbumWithCascade(album)
            false
        } else {
            addAlbumWithSongs(album)
            true
        }
    }

    fun removeAlbumWithCascade(album: AlbumItem) {
        val aKey = albumKey(album.name, album.artist)
        val cascade = cascadeByAlbum[aKey] ?: emptySet()
        cascadeByAlbum.remove(aKey)
        persistCascade()

        val dropSongIds = cascade // only cascade-tracked songs
        val next = _entries.value.filterNot { e ->
            (e.kind == StuffPinKind.ALBUM && e.id == aKey) ||
                (e.kind == StuffPinKind.SONG && e.id in dropSongIds)
        }
        persistEntries(next)

        val dropKeys = buildSet {
            add("${StuffPinKind.ALBUM.name}:$aKey")
            dropSongIds.forEach { add("${StuffPinKind.SONG.name}:$it") }
        }
        val pinKeys = _pinKeys.value.filterNot { it in dropKeys }
        if (pinKeys.size != _pinKeys.value.size) persistPinKeys(pinKeys)
    }

    fun toggleArtist(artist: ArtistItem): Boolean {
        val key = artistKey(artist.name) ?: return false
        val pin = StuffPin(
            kind = StuffPinKind.ARTIST,
            id = key,
            title = artist.displayName,
            subtitle = "Artist"
        )
        return toggleEntry(pin)
    }

    fun toggleSong(song: Song): Boolean {
        val pin = StuffPin(
            kind = StuffPinKind.SONG,
            id = song.songKey,
            title = song.displayTitle,
            subtitle = song.displayArtist
        )
        return toggleEntry(pin)
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

    fun isPinned(kind: StuffPinKind, id: String): Boolean =
        _pinKeys.value.contains("${kind.name}:$id")

    fun isPinned(pin: StuffPin): Boolean = isPinned(pin.kind, pin.id)

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

    @Deprecated("Use addEntry — does not pin", ReplaceWith("addEntry(pin)"))
    fun add(pin: StuffPin) = addEntry(pin)

    @Deprecated("Use removeEntry", ReplaceWith("removeEntry(pin)"))
    fun remove(pin: StuffPin) = removeEntry(pin)

    fun toggle(pin: StuffPin): Boolean = toggleEntry(pin)

    private fun resolvePins(): List<StuffPin> {
        val byKey = _entries.value.associateBy { it.key }
        return _pinKeys.value.mapNotNull { byKey[it] }
    }

    private fun persistEntries(list: List<StuffPin>) {
        _entries.value = list
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(list)).apply()
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

    private fun persistCascade() {
        // Map<String, List<String>> for JSON
        val serializable = cascadeByAlbum.mapValues { it.value.toList() }
        prefs.edit().putString(KEY_CASCADE, json.encodeToString(serializable)).apply()
    }

    private fun loadCascade(): Map<String, Set<String>> {
        val raw = prefs.getString(KEY_CASCADE, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, List<String>>>(raw)
                .mapValues { it.value.toSet() }
        }.getOrElse { emptyMap() }
    }

    private fun loadEntries(): List<StuffPin> {
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
        private const val KEY_CASCADE = "album_cascade_songs"
        const val PIN_SLOTS = 10

        @Deprecated("Use PIN_SLOTS")
        const val GRID_MIN_CELLS = 10
        @Deprecated("No fixed browse cards")
        const val FIXED_BROWSE_COUNT = 0
    }
}
