package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.PlaybackSnapshot
import capital.yuri.yuriplayer.core.player.QueueLane
import capital.yuri.yuriplayer.core.player.RepeatMode
import capital.yuri.yuriplayer.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Same job as Android PlaybackStateStore: queue + position survive restart.
 * Restore is always paused.
 */
class DesktopPlaybackStore(configDir: String) {
    private val file = File(configDir, "playback_state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(snap: PlaybackSnapshot) {
        if (snap.queue.isEmpty() && snap.coldQueue.isEmpty() && snap.hotQueue.isEmpty()) {
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
                hotQueue = snap.hotQueue,
                coldQueue = snap.coldQueue,
                coldOriginal = snap.coldOriginal,
                coldSource = snap.coldSource,
                lane = snap.lane.name,
                indexInLane = snap.indexInLane,
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
            if (dto.queue.isEmpty() && dto.coldQueue.isEmpty() && dto.hotQueue.isEmpty()) {
                return@runCatching null
            }

            // Legacy files only carried `queue` (flattened, queue[0] = current)
            // and `linear` (full context). Reconstruct the lanes so the current
            // track is preserved instead of always pointing at linear[0].
            val isLegacy = dto.coldQueue.isEmpty() && dto.hotQueue.isEmpty()
            val cold = dto.coldQueue.ifEmpty { dto.linear.ifEmpty { dto.queue } }
            val coldOrig = dto.coldOriginal.ifEmpty { dto.linear.ifEmpty { dto.queue } }
            val indexInLane = if (isLegacy) {
                val currentId = dto.queue.firstOrNull()?.songKey
                cold.indexOfFirst { it.songKey == currentId }
                    .takeIf { it >= 0 }
                    ?: dto.indexInLane.coerceIn(cold.indices)
            } else {
                dto.indexInLane.coerceIn(cold.indices)
            }

            PlaybackSnapshot(
                queue = dto.queue,
                linear = dto.linear.ifEmpty { coldOrig },
                index = dto.index,
                history = dto.history,
                shuffle = dto.shuffle,
                repeat = runCatching { RepeatMode.valueOf(dto.repeat) }.getOrDefault(RepeatMode.OFF),
                volume = dto.volume.coerceIn(0f, 1f),
                positionMs = dto.positionMs,
                hotQueue = dto.hotQueue,
                coldQueue = cold,
                coldOriginal = coldOrig,
                coldSource = dto.coldSource,
                lane = runCatching { QueueLane.valueOf(dto.lane) }.getOrDefault(QueueLane.COLD),
                indexInLane = indexInLane
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
        val queue: List<Song> = emptyList(),
        val linear: List<Song> = emptyList(),
        val history: List<Song> = emptyList(),
        val hotQueue: List<Song> = emptyList(),
        val coldQueue: List<Song> = emptyList(),
        val coldOriginal: List<Song> = emptyList(),
        val coldSource: ColdSource? = null,
        val lane: String = "COLD",
        val indexInLane: Int = 0,
        val savedAt: Long = 0L
    )
}
