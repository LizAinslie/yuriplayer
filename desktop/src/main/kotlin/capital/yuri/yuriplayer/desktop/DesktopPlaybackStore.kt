package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.player.PlaybackSnapshot
import capital.yuri.yuriplayer.core.player.RepeatMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Same job as Android [capital.yuri.yuriplayer.player.PlaybackStateStore]:
 * queue + position survive restart. Restore is always paused.
 */
class DesktopPlaybackStore(configDir: String) {
    private val file = File(configDir, "playback_state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(snap: PlaybackSnapshot) {
        if (snap.queue.isEmpty()) {
            clear()
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val dto = Dto(
                positionMs = snap.positionMs.coerceAtLeast(0L),
                index = snap.index,
                shuffle = snap.shuffle,
                repeat = snap.repeat.name,
                volume = snap.volume,
                queue = snap.queue,
                linear = snap.linear,
                history = snap.history,
                savedAt = System.currentTimeMillis()
            )
            val tmp = File(file.parentFile, "playback_state.json.tmp")
            tmp.writeText(json.encodeToString(dto))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun load(): PlaybackSnapshot? {
        if (!file.isFile) return null
        return runCatching {
            val dto = json.decodeFromString<Dto>(file.readText())
            if (dto.queue.isEmpty()) return@runCatching null
            PlaybackSnapshot(
                queue = dto.queue,
                linear = dto.linear.ifEmpty { dto.queue },
                index = dto.index,
                history = dto.history,
                shuffle = dto.shuffle,
                repeat = runCatching { RepeatMode.valueOf(dto.repeat) }.getOrDefault(RepeatMode.OFF),
                volume = dto.volume.coerceIn(0f, 1f),
                positionMs = dto.positionMs
            )
        }.getOrNull()
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    @Serializable
    private data class Dto(
        val positionMs: Long = 0L,
        val index: Int = 0,
        val shuffle: Boolean = false,
        val repeat: String = "OFF",
        val volume: Float = 1f,
        val queue: List<Track> = emptyList(),
        val linear: List<Track> = emptyList(),
        val history: List<Track> = emptyList(),
        val savedAt: Long = 0L
    )
}
