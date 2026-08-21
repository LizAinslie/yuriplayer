package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.core.platform.appDirectories
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

object LocalLibraryScanner {
    private val AUDIO_EXTENSIONS = setOf(
        "flac", "mp3", "ogg", "opus", "m4a", "mp4", "aac",
        "wav", "aiff", "aif", "wma", "alac"
    )
    private val COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.png",
        "AlbumArt.jpg", "front.jpg"
    )

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun defaultRoots(): List<File> =
        appDirectories().defaultMusicRoots.map { File(it) }.filter { it.isDirectory }

    fun scan(roots: List<File> = defaultRoots()): List<Track> {
        if (roots.isEmpty()) return emptyList()
        val out = ArrayList<Track>(512)
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(12)
                .onEnter { dir -> !dir.isHidden && !dir.name.startsWith('.') }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { file ->
                    val path = file.absolutePath
                    if (!seen.add(path)) return@forEach
                    out += readTrack(file)
                }
        }
        out.sortWith(
            compareBy<Track> { it.displayAlbum.lowercase() }
                .thenBy { it.discNumber ?: 1 }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy { it.displayTitle.lowercase() }
        )
        return out
    }

    private fun readTrack(file: File): Track {
        val fallbackTitle = file.nameWithoutExtension
        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            val header = audio.audioHeader
            fun field(key: FieldKey): String? =
                tag?.getFirst(key)?.trim()?.takeIf { it.isNotEmpty() }
            val folderCover = COVER_NAMES
                .map { File(file.parentFile, it) }
                .firstOrNull { it.isFile }
                ?.toURI()
                ?.toString()
            Track(
                id = file.absolutePath,
                uri = file.toURI().toString(),
                title = field(FieldKey.TITLE) ?: fallbackTitle,
                artist = field(FieldKey.ARTIST),
                albumArtist = field(FieldKey.ALBUM_ARTIST),
                album = field(FieldKey.ALBUM),
                durationMs = header?.trackLength?.toLong()?.times(1000),
                trackNumber = field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
                discNumber = field(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
                year = field(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
                genre = field(FieldKey.GENRE),
                artworkUri = folderCover,
                path = file.absolutePath
            )
        } catch (_: Exception) {
            Track(
                id = file.absolutePath,
                uri = file.toURI().toString(),
                title = fallbackTitle,
                path = file.absolutePath
            )
        }
    }
}
