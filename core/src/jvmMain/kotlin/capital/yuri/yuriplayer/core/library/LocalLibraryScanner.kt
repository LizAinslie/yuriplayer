package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.core.platform.appDirectories
import capital.yuri.yuriplayer.core.platform.coverCacheDir
import capital.yuri.yuriplayer.data.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

object LocalLibraryScanner {
    const val SOURCE_LOCAL = "local"
    private val AUDIO_EXTENSIONS = setOf(
        "flac", "mp3", "ogg", "opus", "m4a", "mp4", "aac",
        "wav", "aiff", "aif", "wma", "alac"
    )
    private val COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
        "folder.jpg", "folder.jpeg", "folder.png",
        "AlbumArt.jpg", "AlbumArt.png", "AlbumArtSmall.jpg",
        "front.jpg", "front.png", "album.jpg", "album.png"
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun defaultRoots(): List<File> =
        appDirectories().defaultMusicRoots.map { File(it) }.filter { it.isDirectory }

    fun scan(roots: List<File> = defaultRoots()): List<Song> = scanSongs(roots)

    fun scanSongs(roots: List<File> = defaultRoots()): List<Song> {
        if (roots.isEmpty()) return emptyList()
        val out = ArrayList<Song>(512)
        val seen = HashSet<String>()
        val coverByDir = HashMap<String, String?>()
        File(coverCacheDir()).mkdirs()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(12)
                .onEnter { dir -> !dir.isHidden && !dir.name.startsWith('.') }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { file ->
                    val path = file.absolutePath
                    if (!seen.add(path)) return@forEach
                    out += readSong(file, coverByDir)
                }
        }
        out.sortWith(
            compareBy<Song> { it.displayAlbum.lowercase() }
                .thenBy { it.discNumber ?: 1 }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy { it.displayTitle.lowercase() }
        )
        return out
    }

    private fun readSong(file: File, coverByDir: MutableMap<String, String?>): Song {
        val fallbackTitle = file.nameWithoutExtension
        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            val header = audio.audioHeader
            fun field(key: FieldKey): String? =
                tag?.getFirst(key)?.trim()?.takeIf { it.isNotEmpty() }
            Song(
                id = file.absolutePath.hashCode().toLong(),
                title = field(FieldKey.TITLE) ?: fallbackTitle,
                artist = field(FieldKey.ARTIST),
                albumArtist = field(FieldKey.ALBUM_ARTIST),
                album = field(FieldKey.ALBUM),
                durationMs = header?.trackLength?.toLong()?.times(1000),
                contentUri = file.toURI().toString(),
                albumArtUri = resolveCover(file, audio, coverByDir),
                trackNumber = field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
                discNumber = field(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
                year = field(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
                genre = field(FieldKey.GENRE),
                path = file.absolutePath,
                sourceId = SOURCE_LOCAL
            )
        } catch (_: Exception) {
            Song(
                id = file.absolutePath.hashCode().toLong(),
                title = fallbackTitle,
                contentUri = file.toURI().toString(),
                albumArtUri = resolveCover(file, null, coverByDir),
                path = file.absolutePath,
                sourceId = SOURCE_LOCAL
            )
        }
    }

    private fun resolveCover(
        file: File,
        audio: org.jaudiotagger.audio.AudioFile?,
        coverByDir: MutableMap<String, String?>
    ): String? {
        val dir = file.parentFile ?: return extractEmbedded(file, audio)
        val key = dir.absolutePath
        coverByDir[key]?.let { return it }
        val folder = namedCover(dir) ?: namedCover(dir.parentFile) ?: firstImage(dir)
        if (folder != null) {
            val uri = folder.toURI().toString()
            coverByDir[key] = uri
            return uri
        }
        val embedded = extractEmbedded(file, audio)
        coverByDir[key] = embedded
        return embedded
    }

    private fun namedCover(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null
        for (name in COVER_NAMES) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 32) return f
        }
        return null
    }

    private fun firstImage(dir: File): File? {
        val images = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in IMAGE_EXT && f.length() > 32 &&
                !f.name.startsWith('.') &&
                !f.name.contains("fanart", true) &&
                !f.name.contains("artist", true) &&
                !f.name.contains("logo", true)
        } ?: return null
        return images.minByOrNull { it.name.length }
    }

    private fun extractEmbedded(
        file: File,
        audio: org.jaudiotagger.audio.AudioFile?
    ): String? {
        val cache = File(coverCacheDir(), "${file.parentFile?.absolutePath.hashCode() and 0x7fffffff}.jpg")
        if (cache.isFile && cache.length() > 32) return cache.toURI().toString()
        return try {
            val art = (audio ?: AudioFileIO.read(file)).tag?.firstArtwork ?: return null
            val bytes = art.binaryData?.takeIf { it.size > 32 } ?: return null
            cache.parentFile?.mkdirs()
            cache.writeBytes(bytes)
            cache.toURI().toString()
        } catch (_: Exception) {
            null
        }
    }
}
