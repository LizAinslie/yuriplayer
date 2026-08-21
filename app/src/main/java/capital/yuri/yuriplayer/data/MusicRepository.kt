package capital.yuri.yuriplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class MusicRepository(
    private val context: Context,
    private val settings: LibrarySettings
) {

    companion object {
        private const val TAG = "YuriPlayer.Library"
        private val AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "ogg", "opus", "m4a", "mp4", "aac",
            "wav", "aiff", "aif", "wma", "alac"
        )
        private const val COL_ALBUM_ARTIST = "album_artist"
        private val SKIP_PATH_FRAGMENTS = listOf(
            "/ringtones/", "/notifications/", "/alarms/",
            "/ui/", "/system/media/"
        )
        private val YEAR_REGEX = Regex("""(19|20)\d{2}""")
        private val FOLDER_COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.png",
            "AlbumArt.jpg", "AlbumArt.png",
            "front.jpg", "front.png"
        )
    }

    suspend fun scanLibrary(): List<Song> = withContext(Dispatchers.IO) {
        when (settings.getScanMode()) {
            LibraryScanMode.MANUAL -> scanManualTrees()
            LibraryScanMode.MEDIASTORE -> scanMediaStoreHybrid()
        }
    }

    private suspend fun scanMediaStoreHybrid(): List<Song> {
        requestMediaScan()

        val byKey = LinkedHashMap<String, Song>()
        queryMediaStore().forEach { song ->
            val key = song.path?.lowercase() ?: song.contentUri.toString()
            byKey[key] = song
        }
        scanFilesystem().forEach { song ->
            val key = song.path?.lowercase() ?: return@forEach
            if (!byKey.containsKey(key)) byKey[key] = song
        }
        val withYear = byKey.values.count { it.year != null }
        Log.i(TAG, "mediastore scan: ${byKey.size} tracks ($withYear with year)")
        return byKey.values.toList()
    }

    /**
     * Walk user-granted SAF trees only. Attaches sibling folder cover as
     * [Song.albumArtUri] when present so the art pipeline can open a real image.
     */
    private fun scanManualTrees(): List<Song> {
        val trees = settings.getManualTreeUris()
        if (trees.isEmpty()) {
            Log.w(TAG, "manual scan: no SAF trees configured")
            return emptyList()
        }
        val byKey = LinkedHashMap<String, Song>()
        for (uriString in trees) {
            val treeUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: continue
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null || !root.isDirectory) {
                Log.w(TAG, "manual scan: invalid tree $uriString")
                continue
            }
            Log.i(TAG, "manual scan tree $uriString")
            walkDocumentTree(root, depth = 0, maxDepth = 12) { doc, parent ->
                val name = doc.name ?: return@walkDocumentTree
                if (!isAudioFileName(name)) return@walkDocumentTree
                val uri = doc.uri
                val key = uri.toString()
                if (byKey.containsKey(key)) return@walkDocumentTree
                val tags = readTagsFromUri(uri, fileName = name)
                val coverUri = findFolderCoverUri(parent)
                byKey[key] = Song(
                    id = key.hashCode().toLong(),
                    title = tags.title ?: name.substringBeforeLast('.'),
                    artist = tags.artist,
                    albumArtist = tags.albumArtist,
                    album = tags.album,
                    durationMs = tags.durationMs,
                    contentUri = uri,
                    albumArtUri = coverUri,
                    trackNumber = tags.trackNumber,
                    discNumber = tags.discNumber,
                    year = tags.year,
                    genre = tags.genre,
                    path = tags.pathHint ?: key,
                    mimeType = doc.type ?: mimeFromPath(name)
                )
            }
        }
        Log.i(TAG, "manual scan complete: ${byKey.size} tracks")
        return byKey.values.toList()
    }

    private fun findFolderCoverUri(dir: DocumentFile?): Uri? {
        if (dir == null || !dir.isDirectory) return null
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return null
        }
        val byLower = children.filter { it.isFile }.associateBy { it.name?.lowercase().orEmpty() }
        for (name in FOLDER_COVER_NAMES) {
            val hit = byLower[name.lowercase()] ?: continue
            return hit.uri
        }
        return null
    }

    private fun walkDocumentTree(
        dir: DocumentFile,
        depth: Int,
        maxDepth: Int,
        onFile: (DocumentFile, DocumentFile) -> Unit
    ) {
        if (depth > maxDepth) return
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for ${dir.uri}: ${e.message}")
            return
        }
        for (child in children) {
            when {
                child.isDirectory -> walkDocumentTree(child, depth + 1, maxDepth, onFile)
                child.isFile -> onFile(child, dir)
            }
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private suspend fun requestMediaScan() {
        val roots = settings.getScanRoots().filter { it.exists() }
        if (roots.isEmpty()) return

        val paths = mutableListOf<String>()
        roots.forEach { root ->
            paths += root.absolutePath
            try {
                root.walkTopDown().maxDepth(8).onFail { _, _ -> }
                    .filter { it.isFile && isAudioPath(it.absolutePath) }
                    .forEach { paths += it.absolutePath }
            } catch (e: Exception) {
                Log.w(TAG, "walk failed ${root.absolutePath}", e)
            }
        }
        if (paths.isEmpty()) return

        val unique = paths.distinct()
        Log.i(TAG, "media-scan ${unique.size} paths")
        suspendCancellableCoroutine { cont ->
            val left = AtomicInteger(unique.size)
            fun done() {
                if (left.decrementAndGet() <= 0 && cont.isActive) cont.resume(Unit)
            }
            try {
                MediaScannerConnection.scanFile(
                    context,
                    unique.toTypedArray(),
                    null
                ) { path, uri ->
                    Log.d(TAG, "scanned $path → $uri")
                    done()
                }
            } catch (e: Exception) {
                Log.w(TAG, "media-scan failed", e)
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun isSystemSoundPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase().replace('\\', '/')
        return SKIP_PATH_FRAGMENTS.any { lower.contains(it) }
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
            append(" OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%/music/%'")
            append(" OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%/download/%'")
            AUDIO_EXTENSIONS.forEach { ext ->
                append(" OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%.$ext'")
            }
            append(")")
            append(" AND (${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} > 1000)")
            append(" AND (${MediaStore.Audio.Media.IS_RINGTONE} = 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 OR ${MediaStore.Audio.Media.IS_NOTIFICATION} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_ALARM} = 0 OR ${MediaStore.Audio.Media.IS_ALARM} IS NULL)")
        }

        val cursor = try {
            context.contentResolver.query(collection, projection, selection, null, null)
        } catch (_: IllegalArgumentException) {
            try {
                context.contentResolver.query(
                    collection,
                    projection.copyOfRange(0, projection.size - 1),
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    null
                )
            } catch (_: Exception) {
                null
            }
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
                if (isSystemSoundPath(path)) continue

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
                var year = it.getInt(yearCol).takeIf { y -> y in 1000..2100 }
                var genre: String? = null
                var disc: Int? = null
                val albumId = it.getLong(albumIdCol)

                if (path != null) {
                    val tags = readTagsFromFile(path)
                    if (title == null || title == fileNameTitle(path)) title = tags.title ?: title
                    if (artist == null) artist = tags.artist
                    if (album == null) album = tags.album
                    if (albumArtist == null) albumArtist = tags.albumArtist
                    if (duration == null && tags.durationMs != null) duration = tags.durationMs
                    if (track == null && tags.trackNumber != null) track = tags.trackNumber
                    disc = tags.discNumber
                    if (tags.year != null) year = tags.year
                    if (tags.genre != null) genre = tags.genre
                    if (title == fileNameTitle(path)) title = tags.title
                    if (track == null) track = tags.trackNumber
                    if (title == null || title == fileNameTitle(path)) title = tags.title ?: title
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
                    discNumber = disc,
                    year = year,
                    genre = genre,
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
            if (!root.exists()) {
                Log.w(TAG, "scan root missing: ${root.absolutePath}")
                return@forEach
            }
            Log.i(TAG, "fs-scan ${root.absolutePath}")
            try {
                root.walkTopDown().maxDepth(8).onFail { f, e ->
                    Log.w(TAG, "walk fail $f: ${e.message}")
                }
                    .filter { it.isFile && isAudioPath(it.absolutePath) }
                    .forEach { file ->
                        val path = file.absolutePath
                        if (isSystemSoundPath(path)) return@forEach
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
                            discNumber = tags.discNumber,
                            year = tags.year,
                            genre = tags.genre,
                            path = path,
                            mimeType = mimeFromPath(path)
                        )
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "fs-scan permission denied for ${root.absolutePath}", e)
            } catch (e: Exception) {
                Log.e(TAG, "fs-scan failed ${root.absolutePath}", e)
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
        val discNumber: Int? = null,
        val year: Int? = null,
        val genre: String? = null,
        val pathHint: String? = null
    }

    private fun FileTags.withFilenameFallback(fileName: String?, persistPath: String?): FileTags {
        val inferred = FilenameMetadataParser.parse(
            persistPath ?: fileName
        )
        if (inferred.isEmpty) return this
        val rawName = fileName?.substringBeforeLast('.')
            ?: persistPath?.substringAfterLast('/')?.substringBeforeLast('.')
        val titleNeedsFill = title.isNullOrBlank() || title == rawName
        val next = copy(
            trackNumber = trackNumber ?: inferred.trackNumber,
            discNumber = discNumber ?: inferred.discNumber,
            title = if (titleNeedsFill) inferred.title ?: title else title
        )
        val writeTrack = trackNumber == null && inferred.trackNumber != null
        val writeDisc = discNumber == null && inferred.discNumber != null
        val writeTitle = titleNeedsFill && inferred.title != null && inferred.title != title
        if (persistPath != null && (writeTrack || writeDisc || writeTitle)) {
            persistInferredTags(
                persistPath,
                track = inferred.trackNumber.takeIf { writeTrack },
                disc = inferred.discNumber.takeIf { writeDisc },
                title = inferred.title.takeIf { writeTitle }
            )
        }
        return next
    }

    private fun readTagsFromUri(uri: Uri, fileName: String? = null): FileTags {
        val fromMmr = readTagsWithRetrieverUri(uri)
        val path = uri.path
        val fromJaudio = if (path != null && path.startsWith("/") && File(path).canRead()) {
            readTagsWithJaudio(path)
        } else FileTags()
        return FileTags(
            title = fromMmr.title ?: fromJaudio.title,
            artist = fromMmr.artist ?: fromJaudio.artist,
            albumArtist = fromMmr.albumArtist ?: fromJaudio.albumArtist,
            album = fromMmr.album ?: fromJaudio.album,
            durationMs = fromMmr.durationMs ?: fromJaudio.durationMs,
            trackNumber = fromMmr.trackNumber ?: fromJaudio.trackNumber,
            discNumber = fromMmr.discNumber ?: fromJaudio.discNumber,
            year = fromJaudio.year ?: fromMmr.year,
            genre = fromJaudio.genre ?: fromMmr.genre,
            pathHint = path
        ).withFilenameFallback(
            fileName = fileName ?: path?.substringAfterLast('/'),
            persistPath = path?.takeIf { File(it).isFile }
        )
    }

    private fun readTagsFromFile(path: String): FileTags {
        val fromMmr = readTagsWithRetriever(path)
        val fromJaudio = readTagsWithJaudio(path)
        return FileTags(
            title = fromMmr.title ?: fromJaudio.title,
            artist = fromMmr.artist ?: fromJaudio.artist,
            albumArtist = fromMmr.albumArtist ?: fromJaudio.albumArtist,
            album = fromMmr.album ?: fromJaudio.album,
            durationMs = fromMmr.durationMs ?: fromJaudio.durationMs,
            trackNumber = fromMmr.trackNumber ?: fromJaudio.trackNumber,
            discNumber = fromMmr.discNumber ?: fromJaudio.discNumber,
            year = fromJaudio.year ?: fromMmr.year,
            genre = fromJaudio.genre ?: fromMmr.genre,
            pathHint = path
        ).withFilenameFallback(fileNameTitle(path), persistPath = path)
    }

    private fun readTagsWithRetrieverUri(uri: Uri): FileTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val dateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            FileTags(
                title = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                artist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                albumArtist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)),
                album = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 },
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseTrackNumber(it) },
                discNumber = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                }.getOrNull()?.let { parseTrackNumber(it) },
                year = parseYear(yearRaw) ?: parseYear(dateRaw),
                genre = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
            )
        } catch (_: Exception) {
            FileTags()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readTagsWithRetriever(path: String): FileTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val dateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            FileTags(
                title = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                artist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                albumArtist = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)),
                album = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 },
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseTrackNumber(it) },
                discNumber = runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                }.getOrNull()?.let { parseTrackNumber(it) },
                year = parseYear(yearRaw) ?: parseYear(dateRaw),
                genre = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
            )
        } catch (_: Exception) {
            FileTags()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readTagsWithJaudio(path: String): FileTags {
        return try {
            val audio = AudioFileIO.read(File(path))
            val tag = audio.tag ?: return FileTags()
            fun field(key: FieldKey): String? =
                cleanTag(runCatching { tag.getFirst(key) }.getOrNull())

            val year = parseYear(field(FieldKey.YEAR))
                ?: parseYear(field(FieldKey.ORIGINAL_YEAR))
                ?: parseYear(runCatching { tag.getFirst("DATE") }.getOrNull())
                ?: parseYear(runCatching { tag.getFirst("date") }.getOrNull())
                ?: parseYear(runCatching { tag.getFirst("Year") }.getOrNull())

            FileTags(
                title = field(FieldKey.TITLE),
                artist = field(FieldKey.ARTIST),
                albumArtist = field(FieldKey.ALBUM_ARTIST),
                album = field(FieldKey.ALBUM),
                durationMs = runCatching {
                    audio.audioHeader?.trackLength?.toLong()?.times(1000)?.takeIf { it > 0 }
                }.getOrNull(),
                trackNumber = field(FieldKey.TRACK)?.let { parseTrackNumber(it) },
                discNumber = field(FieldKey.DISC_NO)?.let { parseTrackNumber(it) },
                year = year,
                genre = field(FieldKey.GENRE),
                pathHint = path
            )
        } catch (e: Exception) {
            Log.d(TAG, "jaudio tags failed for $path: ${e.message}")
            FileTags()
        }
    }

    private fun persistInferredTags(
        path: String,
        track: Int?,
        disc: Int?,
        title: String?
    ) {
        if (track == null && disc == null && title.isNullOrBlank()) return
        val file = File(path)
        if (!file.isFile) return
        if (!file.canWrite()) runCatching { file.setWritable(true) }
        if (!file.canWrite()) return
        try {
            runCatching { TagOptionSingleton.getInstance().isAndroid = true }
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            if (track != null) tag.setField(FieldKey.TRACK, track.toString())
            if (disc != null) tag.setField(FieldKey.DISC_NO, disc.toString())
            if (!title.isNullOrBlank()) tag.setField(FieldKey.TITLE, title)
            audio.commit()
            Log.i(TAG, "wrote inferred tags track=$track disc=$disc title='$title' for ${file.name}")
        } catch (e: Exception) {
            Log.d(TAG, "inferred tag write skipped for $path: ${e.message}")
        }
    }

    private fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = YEAR_REGEX.find(raw.trim()) ?: return null
        return match.value.toIntOrNull()?.takeIf { it in 1000..2100 }
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
