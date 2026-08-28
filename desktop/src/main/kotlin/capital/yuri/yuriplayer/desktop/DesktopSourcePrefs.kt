package capital.yuri.yuriplayer.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Preferred playback source per collapsed track identity — same role as
 * Android SourceResolver overrides.
 */
class DesktopSourcePrefs(configDir: String) {
    private val file = File(configDir, "source_prefs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var map: MutableMap<String, String> = load()

    fun snapshot(): Map<String, String> = synchronized(lock) { map.toMap() }

    fun get(identity: String): String? = synchronized(lock) { map[identity] }

    fun set(identity: String, trackId: String) {
        synchronized(lock) {
            map[identity] = trackId
            persist()
        }
    }

    fun clear(identity: String) {
        synchronized(lock) {
            map.remove(identity)
            persist()
        }
    }

    private fun load(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Dto>(file.readText()).preferred.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Dto(map)))
        }
    }

    @Serializable
    private data class Dto(val preferred: Map<String, String> = emptyMap())
}
