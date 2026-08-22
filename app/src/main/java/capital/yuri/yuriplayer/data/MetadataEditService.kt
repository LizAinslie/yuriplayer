package capital.yuri.yuriplayer.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.player.engine.isNetworkUri
import capital.yuri.yuriplayer.player.engine.isVirtualLibraryPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.images.Artwork
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Writes embedded tags into **writable** audio sources.
 *
 * Writable today:
 * - Local filesystem / MediaStore files
 * - SAF document trees (manual library mode)
 *
 * Not writable:
 * - Jellyfin / Subsonic / Navidrome streams (server-owned metadata)
 *
 * Cover art: always also written to app [filesDir]/covers] so the UI has a
 * durable image even when FLAC embed or MediaStore update cannot succeed
 * (common with SAF document URIs).
 */
class MetadataEditService(
    private val context: Context,
    private val libraryIndex: LibraryIndex,
    private val catalog: CatalogRepository,
    private val coverPrefs: AlbumCoverPrefs
) {

    init {
        runCatching {
            TagOptionSingleton.getInstance().isAndroid = true
        }.onFailure {
            log.w(it) { "Could not set TagOptionSingleton.isAndroid" }
        }
    }

    data class SongEdit(
        val title: String?,
        val artist: String?,
        val genre: String? = null
    )

    data class AlbumEdit(
        val albumName: String?,
        val albumArtist: String?,
        val year: Int?,
        val genre: String? = null,
        val coverBytes: ByteArray? = null,
        val coverMime: String? = null
    )

    data class Result(
        val ok: Int,
        val failed: Int,
        val message: String
    )

    sealed class WritableTarget {
        data class FilePath(val file: File) : WritableTarget()
        data class ContentUri(val uri: Uri) : WritableTarget()
    }

    fun isWritableSong(song: Song): Boolean = resolveWritableTarget(song) != null

    fun isLocalFile(song: Song): Boolean = isWritableSong(song)

    fun isWritableAlbum(album: AlbumItem): Boolean =
        album.songs.any { isWritableSong(it) }

    fun isLocalAlbum(album: AlbumItem): Boolean = isWritableAlbum(album)

    fun sourceTypeAllowsTagWrites(type: SourceType): Boolean = when (type) {
        SourceType.LOCAL -> true
        SourceType.WEBDAV -> true
        SourceType.OTHER -> true
        SourceType.JELLYFIN,
        SourceType.SUBSONIC,
        SourceType.NAVIDROME -> false
    }

    suspend fun saveSong(song: Song, edit: SongEdit): Result = withContext(Dispatchers.IO) {
        val target = resolveWritableTarget(song)
            ?: return@withContext Result(0, 1, notWritableMessage(song))
        try {
            when (target) {
                is WritableTarget.FilePath -> {
                    val file = target.file
                    if (!file.canWrite()) runCatching { file.setWritable(true) }
                    writeSongTags(file, edit)
                    scanPaths(listOf(file.absolutePath))
                }
                is WritableTarget.ContentUri -> {
                    writeViaContentUri(target.uri) { tmp -> writeSongTags(tmp, edit) }
                }
            }
            updateMediaStoreSong(song, edit)
            catalog.patchTrackTags(
                songKey = song.songKey,
                title = edit.title,
                artist = edit.artist,
                album = null,
                albumArtist = null,
                year = null,
                genre = edit.genre
            )
            libraryIndex.reloadFromCatalog()
            Result(1, 0, "Saved")
        } catch (e: Exception) {
            log.e(e) { "saveSong failed key=${song.songKey}" }
            Result(0, 1, e.message ?: "Write failed — need storage permission?")
        }
    }

    suspend fun saveAlbum(album: AlbumItem, edit: AlbumEdit): Result = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        val scanned = mutableListOf<String>()

        val writableSongs = album.songs.mapNotNull { s ->
            resolveWritableTarget(s)?.let { s to it }
        }
        if (writableSongs.isEmpty()) {
            return@withContext Result(0, album.songs.size, "No writable files in this album")
        }

        val aKey = albumKey(
            edit.albumName ?: album.name,
            edit.albumArtist ?: album.artist
        )

        // Always persist cover into app storage so UI works even when
        // embed-into-FLAC or MediaStore update cannot (SAF).
        val appCoverPath = edit.coverBytes?.let { bytes ->
            persistAppCover(aKey, bytes)
        }
        if (appCoverPath != null) {
            coverPrefs.setPreferredUri(aKey, appCoverPath)
            runCatching {
                catalog.applyAlbumCover(
                    albumKey = aKey,
                    coverPath = appCoverPath,
                    coverUrl = null,
                    mbid = null
                )
            }
            log.i { "app cover cached $appCoverPath" }
        }

        val coverFile = edit.coverBytes?.let { bytes ->
            val firstFile = writableSongs.firstNotNullOfOrNull { (_, t) ->
                (t as? WritableTarget.FilePath)?.file
            }
            val dir = firstFile?.parentFile
            if (dir != null && dir.isDirectory) {
                val out = File(dir, "cover.jpg")
                try {
                    FileOutputStream(out).use { it.write(bytes) }
                    scanned += out.absolutePath
                    out
                } catch (e: Exception) {
                    log.d { "folder cover.jpg write skipped: ${e.message}" }
                    null
                }
            } else null
        }

        for ((song, target) in writableSongs) {
            try {
                when (target) {
                    is WritableTarget.FilePath -> {
                        val file = target.file
                        if (!file.canWrite()) runCatching { file.setWritable(true) }
                        writeAlbumTags(file, edit, coverFile)
                        scanned += file.absolutePath
                    }
                    is WritableTarget.ContentUri -> {
                        writeViaContentUri(target.uri) { tmp ->
                            writeAlbumTags(tmp, edit, coverFile)
                        }
                    }
                }
                updateMediaStoreAlbumFields(song, edit)
                catalog.patchTrackTags(
                    songKey = song.songKey,
                    title = null,
                    artist = null,
                    album = edit.albumName,
                    albumArtist = edit.albumArtist,
                    year = edit.year,
                    genre = edit.genre
                )
                ok++
            } catch (e: Exception) {
                log.e(e) { "saveAlbum track failed key=${song.songKey}" }
                failed++
            }
        }

        failed += album.songs.size - writableSongs.size

        if (scanned.isNotEmpty()) scanPaths(scanned)
        if (ok > 0 || appCoverPath != null) {
            runCatching { catalog.rebuildRollups() }
            libraryIndex.reloadFromCatalog()
        }
        Result(
            ok = ok,
            failed = failed,
            message = when {
                appCoverPath != null && ok == 0 ->
                    "Cover saved in app (file tags not writable)"
                failed == 0 -> "Saved $ok tracks" +
                    if (appCoverPath != null) " + cover" else ""
                ok == 0 -> "Could not write tags — check storage permission"
                else -> "Saved $ok, failed $failed"
            }
        )
    }

    private fun persistAppCover(albumKeyStr: String, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "covers").also { it.mkdirs() }
            val name = MetadataEnrichmentService.sanitizeFileName(albumKeyStr) + ".jpg"
            val dest = File(dir, name)
            val tmp = File(dir, "tmp-${System.nanoTime()}.jpg")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (dest.isFile && dest.length() > 0L) dest.absolutePath else null
        } catch (e: Exception) {
            log.w(e) { "persistAppCover failed" }
            null
        }
    }

    private fun notWritableMessage(song: Song): String {
        val path = song.path.orEmpty()
        return when {
            isVirtualLibraryPath(path) || isNetworkUri(song.contentUri) ->
                "This track is from a streaming server (e.g. Jellyfin). " +
                    "Edit metadata on a local or cloud file copy."
            else ->
                "No writable file for this track. Metadata edit needs a local file, " +
                    "SAF folder, or a cloud drive mount."
        }
    }

    fun resolveWritableTarget(song: Song): WritableTarget? {
        val path = song.path
        if (isVirtualLibraryPath(path)) return null
        if (isNetworkUri(song.contentUri)) return null

        if (!path.isNullOrBlank() && !path.contains("://")) {
            val f = File(path)
            if (f.isFile) return WritableTarget.FilePath(f)
        }

        if (song.contentUri.scheme.equals("file", ignoreCase = true)) {
            val p = song.contentUri.path
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.isFile) return WritableTarget.FilePath(f)
            }
        }

        if (song.contentUri.scheme.equals("content", ignoreCase = true)) {
            queryMediaStorePath(song.contentUri)?.let { dataPath ->
                val f = File(dataPath)
                if (f.isFile) return WritableTarget.FilePath(f)
            }
            if (canOpenContent(song.contentUri)) {
                return WritableTarget.ContentUri(song.contentUri)
            }
        }

        return null
    }

    fun resolveWritablePath(song: Song): String? =
        (resolveWritableTarget(song) as? WritableTarget.FilePath)?.file?.absolutePath

    private fun queryMediaStorePath(uri: Uri): String? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx < 0) return@use null
                c.getString(idx)?.takeIf { it.isNotBlank() && File(it).isFile }
            }
        } catch (e: Exception) {
            log.d { "DATA query failed for $uri: ${e.message}" }
            null
        }

    private fun canOpenContent(uri: Uri): Boolean {
        val modes = arrayOf("rw", "r")
        for (mode in modes) {
            try {
                context.contentResolver.openFileDescriptor(uri, mode)?.use {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun writeViaContentUri(uri: Uri, mutate: (File) -> Unit) {
        val ext = guessExtension(uri)
        val tmp = File.createTempFile("yp_tag_", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            } ?: error("Cannot read audio from $uri")

            mutate(tmp)

            val written = writeBytesToContentUri(uri, tmp)
            if (!written) error("Cannot write audio back to $uri")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun writeBytesToContentUri(uri: Uri, file: File): Boolean {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
                return true
            }
        } catch (e: Exception) {
            log.d { "openOutputStream wt failed for $uri: ${e.message}" }
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                return true
            }
        } catch (e: Exception) {
            log.d { "openFileDescriptor rwt failed for $uri: ${e.message}" }
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                return true
            }
        } catch (e: Exception) {
            log.d { "openFileDescriptor rw failed for $uri: ${e.message}" }
        }
        return false
    }

    private fun guessExtension(uri: Uri): String {
        val fromPath = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromPath != null) return fromPath
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when (mime) {
            "audio/flac" -> "flac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            else -> "audio"
        }
    }

    private fun writeSongTags(file: File, edit: SongEdit) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        setOrDelete(tag, FieldKey.TITLE, edit.title)
        setOrDelete(tag, FieldKey.ARTIST, edit.artist)
        setOrDelete(tag, FieldKey.GENRE, edit.genre)
        audio.commit()
    }

    private fun writeAlbumTags(file: File, edit: AlbumEdit, coverFile: File?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        setOrDelete(tag, FieldKey.ALBUM, edit.albumName)
        setOrDelete(tag, FieldKey.ALBUM_ARTIST, edit.albumArtist)
        if (edit.year != null && edit.year in 1000..2100) {
            tag.setField(FieldKey.YEAR, edit.year.toString())
        }
        if (edit.genre != null) {
            setOrDelete(tag, FieldKey.GENRE, edit.genre)
        }

        val art = when {
            coverFile != null && coverFile.isFile ->
                createAndroidArtworkFromFile(coverFile, edit.coverMime)
            edit.coverBytes != null ->
                createAndroidArtwork(edit.coverBytes, edit.coverMime ?: "image/jpeg")
            else -> null
        }
        if (art != null) {
            try {
                runCatching { tag.deleteArtworkField() }
                tag.setField(art)
            } catch (t: Throwable) {
                // FLAC embed often fails on Android; app cover cache still holds the image.
                log.d { "embedded art skipped for ${file.name}: ${t.javaClass.simpleName}: ${t.message}" }
            }
        }
        audio.commit()
    }

    private fun createAndroidArtwork(bytes: ByteArray, mime: String): Artwork {
        val art = AndroidArtwork()
        art.binaryData = bytes
        art.mimeType = mime.ifBlank { "image/jpeg" }
        art.description = "Cover"
        art.pictureType = FRONT_COVER_PICTURE_TYPE
        return art
    }

    private fun createAndroidArtworkFromFile(file: File, mimeHint: String?): Artwork {
        val bytes = file.readBytes()
        val mime = mimeHint?.takeIf { it.isNotBlank() } ?: when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
        return createAndroidArtwork(bytes, mime)
    }

    private fun setOrDelete(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            try {
                tag.deleteField(key)
            } catch (_: Exception) {
            }
        } else {
            tag.setField(key, v)
        }
    }

    /** Only MediaStore audio rows support update — SAF document URIs throw. */
    private fun isMediaStoreAudioUri(uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val auth = uri.authority.orEmpty()
        return auth.contains("media", ignoreCase = true)
    }

    private fun updateMediaStoreSong(song: Song, edit: SongEdit) {
        if (!isMediaStoreAudioUri(song.contentUri)) return
        val values = ContentValues().apply {
            edit.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
            edit.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            if (Build.VERSION.SDK_INT >= 30 && edit.genre != null) {
                put(MediaStore.Audio.Media.GENRE, edit.genre)
            }
        }
        if (values.size() == 0) return
        runCatching {
            context.contentResolver.update(song.contentUri, values, null, null)
        }.onFailure {
            log.d { "MediaStore song update skipped: ${it.message}" }
        }
    }

    private fun updateMediaStoreAlbumFields(song: Song, edit: AlbumEdit) {
        if (!isMediaStoreAudioUri(song.contentUri)) return
        val values = ContentValues().apply {
            edit.albumName?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            edit.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
            if (Build.VERSION.SDK_INT >= 30 && edit.genre != null) {
                put(MediaStore.Audio.Media.GENRE, edit.genre)
            }
        }
        if (values.size() == 0) return
        runCatching {
            context.contentResolver.update(song.contentUri, values, null, null)
        }.onFailure {
            log.d { "MediaStore album update skipped: ${it.message}" }
        }
    }

    private fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(
            context,
            paths.distinct().toTypedArray(),
            null,
            null
        )
    }

    suspend fun readImageBytes(uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            bytes to mime
        } catch (e: Exception) {
            log.e(e) { "readImageBytes failed" }
            null
        }
    }

    companion object {
        private val log = yuriLog("MetadataEdit")
        private const val FRONT_COVER_PICTURE_TYPE = 3
    }
}
