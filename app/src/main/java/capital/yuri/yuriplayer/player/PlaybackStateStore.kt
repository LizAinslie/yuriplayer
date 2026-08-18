package capital.yuri.yuriplayer.player

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.json.AppJson
import kotlinx.serialization.Serializable
import java.io.File

class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = AppJson.json

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
            val dto = PlaybackStateDto(
                version = VERSION,
                positionMs = positionMs.coerceAtLeast(0L),
                playWhenReady = playWhenReady,
                lane = snapshot.lane,
                indexInLane = snapshot.indexInLane,
                shuffleEnabled = snapshot.shuffleEnabled,
                repeatMode = snapshot.repeatMode,
                savedAt = System.currentTimeMillis(),
                hotQueue = snapshot.hotQueue,
                coldQueue = snapshot.coldQueue,
                coldOriginal = snapshot.coldOriginal,
                playedStack = snapshot.playedStack,
                coldSource = snapshot.coldSource
            )
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(PlaybackStateDto.serializer(), dto))
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
            val text = file.readText()
            val dto = json.decodeFromString(PlaybackStateDto.serializer(), text)

            // Legacy: single "queue" array before hot/cold split
            if (dto.coldQueue.isEmpty() && dto.hotQueue.isEmpty() && !dto.queue.isNullOrEmpty()) {
                return loadLegacy(dto)
            }

            val hot = dto.hotQueue
            val cold = dto.coldQueue
            if (hot.isEmpty() && cold.isEmpty()) return null

            val coldOriginal = dto.coldOriginal.ifEmpty { cold }

            SavedPlayback(
                snapshot = QueueSnapshot(
                    hotQueue = hot,
                    coldQueue = cold,
                    coldOriginal = coldOriginal,
                    coldSource = dto.coldSource?.takeIf { it.id.isNotBlank() },
                    lane = dto.lane,
                    indexInLane = dto.indexInLane,
                    shuffleEnabled = dto.shuffleEnabled,
                    repeatMode = dto.repeatMode,
                    playedStack = dto.playedStack
                ),
                positionMs = dto.positionMs,
                playWhenReady = dto.playWhenReady
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load playback state", e)
            null
        }
    }

    private fun loadLegacy(dto: PlaybackStateDto): SavedPlayback? {
        val queue = dto.queue ?: return null
        if (queue.isEmpty()) return null
        val index = (dto.index ?: 0).coerceIn(0, queue.lastIndex)
        return SavedPlayback(
            snapshot = QueueSnapshot(
                hotQueue = emptyList(),
                coldQueue = queue,
                coldOriginal = queue,
                lane = QueueLane.COLD,
                indexInLane = index,
                shuffleEnabled = false,
                repeatMode = RepeatMode.OFF,
                playedStack = emptyList()
            ),
            positionMs = dto.positionMs,
            playWhenReady = dto.playWhenReady
        )
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

    @Serializable
    data class SavedPlayback(
        val snapshot: QueueSnapshot,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val FILE_NAME = "playback_state.json"
        /** v4: playedStack for Previous across restarts. */
        private const val VERSION = 4
    }
}

@Serializable
private data class PlaybackStateDto(
    val version: Int = 4,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val lane: QueueLane = QueueLane.COLD,
    val indexInLane: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val savedAt: Long = 0L,
    val hotQueue: List<Song> = emptyList(),
    val coldQueue: List<Song> = emptyList(),
    val coldOriginal: List<Song> = emptyList(),
    val playedStack: List<Song> = emptyList(),
    val coldSource: ColdSource? = null,
    /** Pre–hot/cold legacy single queue. */
    val queue: List<Song>? = null,
    val index: Int? = null
)
