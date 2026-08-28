package capital.yuri.yuriplayer.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * Desktop My Stuff: liked songs + pinned albums/artists, newest first.
 */
class DesktopCollection(configDir: String) {
    private val file = File(configDir, "collection.properties")

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    private val _pinned = MutableStateFlow<List<Pin>>(emptyList())
    val pinned: StateFlow<List<Pin>> = _pinned.asStateFlow()

    private val _saved = MutableStateFlow<List<Pin>>(emptyList())
    val saved: StateFlow<List<Pin>> = _saved.asStateFlow()

    data class Pin(val kind: Kind, val id: String, val title: String, val subtitle: String)

    enum class Kind { ALBUM, ARTIST, SONG, PLAYLIST }

    init {
        load()
    }

    fun isLiked(trackId: String): Boolean = trackId in _liked.value

    fun toggleLike(trackId: String) {
        val next = _liked.value.toMutableSet()
        if (!next.add(trackId)) next.remove(trackId)
        _liked.value = next
        persist()
    }

    fun pin(pin: Pin) {
        val cur = _pinned.value.toMutableList()
        cur.removeAll { it.kind == pin.kind && it.id == pin.id }
        cur.add(0, pin)
        _pinned.value = cur
        persist()
    }

    fun isPinned(kind: Kind, id: String): Boolean =
        _pinned.value.any { it.kind == kind && it.id == id }

    fun isSaved(kind: Kind, id: String): Boolean =
        _saved.value.any { it.kind == kind && it.id == id }

    fun toggleSaved(pin: Pin) {
        val cur = _saved.value.toMutableList()
        val had = cur.removeAll { it.kind == pin.kind && it.id == pin.id }
        if (!had) cur.add(0, pin)
        _saved.value = cur
        persist()
    }

    fun togglePin(pin: Pin) {
        if (isPinned(pin.kind, pin.id)) unpin(pin.kind, pin.id) else pin(pin)
    }

    fun unpin(kind: Kind, id: String) {
        _pinned.value = _pinned.value.filterNot { it.kind == kind && it.id == id }
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            _liked.value = p.getProperty("liked").orEmpty()
                .split('|').filter { it.isNotBlank() }.toSet()
            val pins = p.getProperty("pins").orEmpty().split('\n').mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                Pin(kind, parts[1], parts[2], parts[3])
            }
            _pinned.value = pins
            val saved = p.getProperty("saved").orEmpty().split('\n').mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                Pin(kind, parts[1], parts[2], parts[3])
            }
            _saved.value = saved
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("liked", _liked.value.joinToString("|"))
            p.setProperty(
                "pins",
                _pinned.value.joinToString("\n") { "${it.kind}\t${it.id}\t${it.title}\t${it.subtitle}" }
            )
            p.setProperty(
                "saved",
                _saved.value.joinToString("\n") { "${it.kind}\t${it.id}\t${it.title}\t${it.subtitle}" }
            )
            file.outputStream().use { p.store(it, "Yuri Player collection") }
        }
    }
}
