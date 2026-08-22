package capital.yuri.yuriplayer.data

import android.content.Context
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.json.AppJson
import kotlinx.serialization.Serializable
import java.io.File

class LibraryCache(context: Context) {

    private val cacheDir = context.cacheDir
    private val file = File(cacheDir, FILE_NAME)
    private val tmpFile = File(cacheDir, "$FILE_NAME.tmp")
    private val json = AppJson.json

    fun load(): CachedLibrary? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val dto = json.decodeFromString(LibraryCacheDto.serializer(), file.readText())
            val songs = dto.songs.map { it.cleaned() }
            CachedLibrary(songs, dto.scannedAt)
        } catch (e: Exception) {
            log.w(e) { "Failed to load library cache" }
            null
        }
    }

    fun save(songs: List<Song>) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val dto = LibraryCacheDto(
                version = CACHE_VERSION,
                scannedAt = System.currentTimeMillis(),
                songs = songs
            )
            tmpFile.writeText(json.encodeToString(LibraryCacheDto.serializer(), dto))
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to save library cache" }
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
            if (tmpFile.exists()) tmpFile.delete()
        } catch (_: Exception) {}
    }

    fun cacheFilePath(): String = file.absolutePath

    companion object {
        private val log = yuriLog("LibraryCache")
        private const val FILE_NAME = "library_index.json"
        private const val CACHE_VERSION = 3
    }
}

@Serializable
private data class LibraryCacheDto(
    val version: Int = 3,
    val scannedAt: Long = 0L,
    val songs: List<Song> = emptyList()
)

data class CachedLibrary(
    val songs: List<Song>,
    val scannedAt: Long
)

/** Strip legacy placeholder tag strings from older caches. */
private fun Song.cleaned(): Song {
    fun clean(s: String?): String? = s?.takeIf {
        it.isNotBlank() &&
            !it.equals("<unknown>", true) &&
            !it.equals("Unknown Artist", true) &&
            !it.equals("Unknown Album", true)
    }
    return copy(
        title = clean(title),
        artist = clean(artist),
        albumArtist = clean(albumArtist),
        album = clean(album),
        trackNumber = trackNumber?.takeIf { it > 0 },
        year = year?.takeIf { it > 0 }
    )
}
