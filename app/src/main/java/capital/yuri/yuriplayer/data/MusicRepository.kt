package capital.yuri.yuriplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local library scanner tuned for Android 8.1 (API 27).
 *
 * Oreo / many OEM builds (including LG) often:
 * - never index .flac into MediaStore, or
 * - index them with IS_MUSIC = 0
 *
 * So we:
 * 1. Query MediaStore with a broad filter (not just IS_MUSIC)
 * 2. Walk common music folders for known extensions as a fallback
 * 3. Prefer embedded tags via MediaMetadataRetriever when needed
 */
class MusicRepository(private val context: Context) {

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "ogg", "opus", "m4a", "mp4", "aac",
            "wav", "aiff", "aif", "wma", "alac"
        )

        private val SCAN_DIRS = listOf(
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS,
            "FLAC",
            "Music",
            "YuriTest"
        )
    }

    suspend fun getAllSongs(sortMode: SortMode = SortMode.TITLE): List<Song> =
        withContext(Dispatchers.IO) {
            val byKey = LinkedHashMap<String, Song>()

            // 1) MediaStore (whatever the system did index)
            queryMediaStore().forEach { song ->
                val key = song.path?.lowercase() ?: song.contentUri.toString()
                byKey[key] = song
            }

            // 2) Filesystem fallback — picks up FLACs the scanner ignored
            scanFilesystem().forEach { song ->
                val key = song.path?.lowercase() ?: return@forEach
                if (!byKey.containsKey(key)) {
                    byKey[key] = song
                }
            }

            sortSongs(byKey.values.toList(), sortMode)
        }

    private fun queryMediaStore(): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // DATA still exists and is readable on API 27
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Do NOT rely solely on IS_MUSIC — FLACs on Oreo frequently fail that flag.
        val selection = buildString {
            append("(")
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'")
            AUDIO_EXTENSIONS.forEach { ext ->
                append(" OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%.$ext'")
            }
            append(")")
            // Skip obvious tiny system sounds when possible
            append(" AND (${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} > 1000)")
        }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol)
                val mime = cursor.getString(mimeCol)

                // Skip non-audio leftovers
                if (path != null && !isAudioPath(path) && mime?.startsWith("audio/") != true) {
                    continue
                }

                var title = cursor.getString(titleCol)
                var artist = cursor.getString(artistCol)
                var album = cursor.getString(albumCol)
                var duration = cursor.getLong(durationCol)
                var track = cursor.getInt(trackCol)
                // MediaStore TRACK is often like 1001 for disc 1 track 1
                if (track >= 1000) track %= 1000
                val year = cursor.getInt(yearCol)
                val albumId = cursor.getLong(albumIdCol)

                // If MediaStore gave us garbage/empty tags, read the file itself
                if (path != null && needsTagRefresh(title, artist, album, path)) {
                    val tags = readTagsFromFile(path)
                    title = tags.title ?: title
                    artist = tags.artist ?: artist
                    album = tags.album ?: album
                    if (duration <= 0 && tags.durationMs > 0) duration = tags.durationMs
                    if (track <= 0 && tags.trackNumber > 0) track = tags.trackNumber
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val albumArtUri = if (albumId > 0) {
                    ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )
                } else null

                songs += Song(
                    id = id,
                    title = title?.takeIf { it.isNotBlank() } ?: fileNameTitle(path),
                    artist = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist",
                    album = album?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album",
                    durationMs = duration,
                    contentUri = contentUri,
                    albumArtUri = albumArtUri,
                    trackNumber = track,
                    year = year,
                    path = path,
                    mimeType = mime
                )
            }
        }

        return songs
    }

    private fun scanFilesystem(): List<Song> {
        val songs = mutableListOf<Song>()
        val roots = mutableListOf<File>()

        // Public external dirs
        SCAN_DIRS.forEach { name ->
            val standard = Environment.getExternalStoragePublicDirectory(name)
            if (standard != null) roots += standard
            roots += File(Environment.getExternalStorageDirectory(), name)
        }
        roots += Environment.getExternalStorageDirectory()

        val seen = HashSet<String>()
        roots.distinct().forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .maxDepth(6)
                .onFail { _, _ -> } // ignore unreadable dirs
                .filter { it.isFile && isAudioPath(it.absolutePath) }
                .forEach { file ->
                    val path = file.absolutePath
                    if (!seen.add(path.lowercase())) return@forEach

                    val tags = readTagsFromFile(path)
                    val uri = Uri.fromFile(file)
                    // Negative synthetic ids for pure filesystem entries
                    val id = path.hashCode().toLong()

                    songs += Song(
                        id = id,
                        title = tags.title ?: file.nameWithoutExtension,
                        artist = tags.artist ?: "Unknown Artist",
                        album = tags.album ?: "Unknown Album",
                        durationMs = tags.durationMs,
                        contentUri = uri,
                        albumArtUri = null,
                        trackNumber = tags.trackNumber,
                        year = tags.year,
                        path = path,
                        mimeType = mimeFromPath(path)
                    )
                }
        }

        return songs
    }

    private data class FileTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0,
        val trackNumber: Int = 0,
        val year: Int = 0
    )

    private fun readTagsFromFile(path: String): FileTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.let { parseTrackNumber(it) } ?: 0
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.take(4)?.toIntOrNull() ?: 0

            FileTags(title, artist, album, duration, track, year)
        } catch (_: Exception) {
            FileTags()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseTrackNumber(raw: String): Int {
        // Handles "3", "3/12", "03"
        val first = raw.split('/', ' ', limit = 2).firstOrNull() ?: return 0
        return first.trim().toIntOrNull()?.let { if (it >= 1000) it % 1000 else it } ?: 0
    }

    private fun needsTagRefresh(title: String?, artist: String?, album: String?, path: String): Boolean {
        if (title.isNullOrBlank() || title == fileNameTitle(path)) return true
        if (artist.isNullOrBlank() || artist == "<unknown>") return true
        if (album.isNullOrBlank() || album == "<unknown>") return true
        return false
    }

    private fun isAudioPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun mimeFromPath(path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "flac" -> "audio/flac"
                "opus" -> "audio/opus"
                "ogg" -> "audio/ogg"
                else -> "audio/*"
            }
    }

    private fun fileNameTitle(path: String?): String {
        if (path == null) return "Unknown"
        return File(path).nameWithoutExtension
    }

    private fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> {
        return when (mode) {
            SortMode.TITLE -> songs.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortMode.ARTIST -> songs.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.trackNumber }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortMode.ALBUM -> songs.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.trackNumber }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            SortMode.TRACK -> songs.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy { it.trackNumber }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }
    }
}
