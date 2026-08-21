package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable library index + per-source scan cursors, matching mobile's
 * Room catalog + [ScanCheckpointStore]. Survives process death so a
 * 40k-track Jellyfin walk does not restart from zero.
 */
class DesktopIndexStore(cacheDir: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tracksFile = File(cacheDir, "index-tracks.json")
    private val cpFile = File(cacheDir, "index-checkpoints.json")

    fun loadTracks(): List<Track> {
        if (!tracksFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<Track>>(tracksFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveTracks(tracks: List<Track>) {
        runCatching {
            tracksFile.parentFile?.mkdirs()
            val tmp = File(tracksFile.parentFile, "${tracksFile.name}.tmp")
            tmp.writeText(json.encodeToString(tracks))
            if (!tmp.renameTo(tracksFile)) {
                tmp.copyTo(tracksFile, overwrite = true)
                tmp.delete()
            }
            PlaylistLog.index("saved ${tracks.size} tracks → ${tracksFile.absolutePath}")
        }.onFailure {
            PlaylistLog.index("save FAILED: ${it.message}")
        }
    }

    fun loadCheckpoints(): List<StoredCheckpoint> {
        if (!cpFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredCheckpoint>>(cpFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveCheckpoints(rows: List<StoredCheckpoint>) {
        runCatching {
            cpFile.parentFile?.mkdirs()
            cpFile.writeText(json.encodeToString(rows))
        }
    }

    @Serializable
    data class StoredCheckpoint(
        val id: String,
        val name: String,
        val status: String,
        val startIndex: Int = 0,
        val delivered: Int = 0,
        val totalHint: Int? = null,
        val count: Int = 0,
        val detail: String = ""
    )
}
