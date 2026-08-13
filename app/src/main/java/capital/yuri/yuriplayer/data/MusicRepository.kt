package capital.yuri.yuriplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(
    private val context: Context,
    private val settings: LibrarySettings
) {

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "ogg", "opus", "m4a", "mp4", "aac",
            "wav", "aiff", "aif", "wma", "alac"
        )

        // Column exists on many API 27 devices even without the MediaStore constant
        private const val COL_ALBUM_ARTIST = "album_artist"
    }

    suspend fun scanLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val byKey = LinkedHashMap<String, Song>()

        queryMediaStore().forEach { song ->
            val key = song.path?.lowercase() ?: song.contentUri.toString()
            byKey[key] = song
        }

        scanFilesystem().forEach { song ->
            val key = song.path?.lowercase() ?: return@forEach
            if (!byKey.containsKey(key)) {
                byKey[key] = song
            }
        }

        byKey.values.toList()
    }

    private fun queryMediaStore(): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

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
            MediaStore.Audio.Media.MIME_TYPE,
            COL_ALBUM_ARTIST
        )

        val selection = buildString {
            append("(")
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'")
            AUDIO_EXTENSIONS.forEach { ext ->
                append(" OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%.$ext'")
            }
            append(")")
            append(" AND (${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} > 1000)")
        }

        // Prefer projection without album_artist if the OEM column is missing
        val cursor = try {
            context.contentResolver.query(collection, projection, selection, null, null)
        } catch (_: IllegalArgumentException) {
            context.contentResolver.query(
                collection,
                projection.copyOfRange(0, projection.size - 1),
                selection,
                null,
                null
            )
        } ?: return songs

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumArtistCol = it.getColumnIndex(COL_ALBUM_ARTIST)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val path = it.getString(dataCol)
                val mime = it.getString(mimeCol)

                if (path != null && !isAudioPath(path) && mime?.startsWith("audio/") != true) {
                    continue
                }

                var title = it.getString(titleCol)
                var artist = it.getString(artistCol)
                var album = it.getString(albumCol)
                var albumArtist = if (albumArtistCol >= 0) it.getString(albumArtistCol) else null
                var duration = it.getLong(durationCol)
                var track = it.getInt(trackCol)
                if (track >= 1000) track %= 1000
                val year = it.getInt(yearCol)
                val albumId = it.getLong(albumIdCol)

                // Always prefer file tags for album artist when possible (Oreo MediaStore is spotty)
                if (path != null) {
                    val tags = readTagsFromFile(path)
                    if (needsTagRefresh(title, artist, album, path)) {
                        title = tags.title ?: title
                        artist = tags.artist ?: artist
                        album = tags.album ?: album
                        if (duration <= 0 && tags.durationMs > 0) duration = tags.durationMs
                        if (track <= 0 && tags.trackNumber > 0) track = tags.trackNumber
                    }
                    if (albumArtist.isNullOrBlank()) {
                        albumArtist = tags.albumArtist
                    }
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

                val cleanArtist = artist?.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: "Unknown Artist"
                val cleanAlbumArtist = albumArtist?.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: ""

                songs += Song(
                    id = id,
                    title = title?.takeIf { t -> t.isNotBlank() } ?: fileNameTitle(path),
                    artist = cleanArtist,
                    albumArtist = cleanAlbumArtist,
                    album = album?.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: "Unknown Album",
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
        val roots = settings.getScanRoots()
        val seen = HashSet<String>()

        roots.forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .maxDepth(8)
                .onFail { _, _ -> }
                .filter { it.isFile && isAudioPath(it.absolutePath) }
                .forEach { file ->
                    val path = file.absolutePath
                    if (!seen.add(path.lowercase())) return@forEach

                    val tags = readTagsFromFile(path)
                    songs += Song(
                        id = path.hashCode().toLong(),
                        title = tags.title ?: file.nameWithoutExtension,
                        artist = tags.artist ?: "Unknown Artist",
                        albumArtist = tags.albumArtist ?: "",
                        album = tags.album ?: "Unknown Album",
                        durationMs = tags.durationMs,
                        contentUri = Uri.fromFile(file),
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
        val albumArtist: String? = null,
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
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.let { parseTrackNumber(it) } ?: 0
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.take(4)?.toIntOrNull() ?: 0
            FileTags(title, artist, albumArtist, album, duration, track, year)
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
}
