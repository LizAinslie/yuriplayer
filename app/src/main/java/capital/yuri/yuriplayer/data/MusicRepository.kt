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
            if (!byKey.containsKey(key)) byKey[key] = song
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

        val cursor = try {
            context.contentResolver.query(collection, projection, selection, null, null)
        } catch (_: IllegalArgumentException) {
            context.contentResolver.query(
                collection,
                projection.copyOfRange(0, projection.size - 1),
                selection, null, null
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
                if (path != null && !isAudioPath(path) && mime?.startsWith("audio/") != true) continue

                var title = cleanTag(it.getString(titleCol))
                var artist = cleanTag(it.getString(artistCol))
                var album = cleanTag(it.getString(albumCol))
                var albumArtist = if (albumArtistCol >= 0) cleanTag(it.getString(albumArtistCol)) else null
                var duration = it.getLong(durationCol).takeIf { d -> d > 0 }
                var track = it.getInt(trackCol).let { t ->
                    val n = if (t >= 1000) t % 1000 else t
                    n.takeIf { it > 0 }
                }
                val year = it.getInt(yearCol).takeIf { y -> y > 0 }
                val albumId = it.getLong(albumIdCol)

                if (path != null) {
                    val tags = readTagsFromFile(path)
                    if (title == null || title == fileNameTitle(path)) title = tags.title ?: title
                    if (artist == null) artist = tags.artist
                    if (album == null) album = tags.album
                    if (albumArtist == null) albumArtist = tags.albumArtist
                    if (duration == null && tags.durationMs != null) duration = tags.durationMs
                    if (track == null && tags.trackNumber != null) track = tags.trackNumber
                }

                // If MediaStore title is just the filename, treat as untagged title
                if (path != null && title == fileNameTitle(path)) {
                    // keep as null for isTagged/hasTitle purposes unless other tags exist
                    // still useful for display via path — store null to mark untagged title
                    val tags = readTagsFromFile(path)
                    title = tags.title // may still be null
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                val albumArtUri = if (albumId > 0) {
                    ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    )
                } else null

                songs += Song(
                    id = id,
                    title = title,
                    artist = artist,
                    albumArtist = albumArtist,
                    album = album,
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
            root.walkTopDown().maxDepth(8).onFail { _, _ -> }
                .filter { it.isFile && isAudioPath(it.absolutePath) }
                .forEach { file ->
                    val path = file.absolutePath
                    if (!seen.add(path.lowercase())) return@forEach
                    val tags = readTagsFromFile(path)
                    songs += Song(
                        id = path.hashCode().toLong(),
                        title = tags.title,
                        artist = tags.artist,
                        albumArtist = tags.albumArtist,
                        album = tags.album,
                        durationMs = tags.durationMs,
                        contentUri = Uri.fromFile(file),
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
        val durationMs: Long? = null,
        val trackNumber: Int? = null,
        val year: Int? = null
    )

    private fun readTagsFromFile(path: String): FileTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            FileTags(
                title = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                artist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                albumArtist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)),
                album = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 },
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseTrackNumber(it) },
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.take(4)?.toIntOrNull()?.takeIf { it > 0 }
            )
        } catch (_: Exception) {
            FileTags()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun cleanTag(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.equals("<unknown>", true)) return null
        if (t.equals("Unknown", true)) return null
        if (t.equals("Unknown Artist", true)) return null
        if (t.equals("Unknown Album", true)) return null
        return t
    }

    private fun parseTrackNumber(raw: String): Int? {
        val first = raw.split('/', ' ', limit = 2).firstOrNull() ?: return null
        val n = first.trim().toIntOrNull() ?: return null
        val track = if (n >= 1000) n % 1000 else n
        return track.takeIf { it > 0 }
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

    private fun fileNameTitle(path: String?): String? {
        if (path == null) return null
        return File(path).nameWithoutExtension
    }
}
