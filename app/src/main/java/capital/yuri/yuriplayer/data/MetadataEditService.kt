package capital.yuri.yuriplayer.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import capital.yuri.yuriplayer.data.source.SourceOffering
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.isTagWritable
import capital.yuri.yuriplayer.data.source.supportsEmbeddedTagWrites
import capital.yuri.yuriplayer.data.source.writableOfferings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileOutputStream

class MetadataEditService(
    private val context: Context,
    private val libraryIndex: LibraryIndex
) {

    data class SongEdit(
        val title: String?,
        val artist: String?,
        /** Genre string (semicolon-separated ok). */
        val genre: String? = null
    )

    data class AlbumEdit(
        val albumName: String?,
        val albumArtist: String?,
        val year: Int?,
        /** Applied to every track when set. */
        val genre: String? = null,
        val coverBytes: ByteArray? = null,
        val coverMime: String? = null
    )

    data class Result(
        val ok: Int,
        val failed: Int,
        val message: String
    )

    /** True when the song has a resolvable local/SAF path that we can write tags into. */
    fun isLocalFile(song: Song): Boolean {
        val path = resolveWritablePath(song) ?: return false
        return File(path).isFile
    }

    fun isLocalAlbum(album: AlbumItem): Boolean =
        album.songs.any { isLocalFile(it) }

    /**
     * True when at least one offering is tag-writable (LOCAL / WEBDAV / future cloud).
     * Jellyfin + Navidrome (Subsonic/OpenSubsonic) never qualify.
     */
    fun hasWritableOffering(offerings: List<SourceOffering>): Boolean =
        offerings.any { it.isTagWritable() }

    fun canEditSong(song: Song, offerings: List<SourceOffering> = emptyList()): Boolean {
        if (offerings.isNotEmpty()) return hasWritableOffering(offerings)
        // Fallback when multi-source list is not yet wired: local path only.
        return isLocalFile(song)
    }

    fun canEditAlbum(album: AlbumItem, offerings: List<SourceOffering> = emptyList()): Boolean {
        if (offerings.isNotEmpty()) return hasWritableOffering(offerings)
        return isLocalAlbum(album)
    }

    /**
     * Write song tags only into [targets] (must already be filtered to writable offerings).
     * If [targets] is empty, falls back to the single local path for [song] when present.
     */
    suspend fun saveSong(
        song: Song,
        edit: SongEdit,
        targets: List<SourceOffering> = emptyList()
    ): Result = withContext(Dispatchers.IO) {
        val writeTargets = resolveWriteTargets(song, targets)
        if (writeTargets.isEmpty()) {
            return@withContext Result(
                0,
                1,
                "No writable source selected (Jellyfin / Subsonic are read-only)"
            )
        }

        var ok = 0
        var failed = 0
        val scanned = mutableListOf<String>()
        for (path in writeTargets) {
            try {
                val file = File(path)
                if (!file.canWrite()) runCatching { file.setWritable(true) }
                writeSongTags(file, edit)
                scanned += path
                ok++
            } catch (e: Exception) {
                Log.e(TAG, "saveSong failed path=$path", e)
                failed++
            }
        }
        if (ok > 0) {
            updateMediaStoreSong(song, edit)
            scanPaths(scanned)
            libraryIndex.refresh()
        }
        Result(
            ok = ok,
            failed = failed,
            message = when {
                failed == 0 -> if (ok == 1) "Saved" else "Saved $ok sources"
                ok == 0 -> "Write failed — need storage permission?"
                else -> "Saved $ok, failed $failed"
            }
        )
    }

    /**
     * Write album-level tags only into writable targets.
     * When [targets] is empty, uses every local path under the album (legacy path).
     */
    suspend fun saveAlbum(
        album: AlbumItem,
        edit: AlbumEdit,
        targets: List<SourceOffering> = emptyList()
    ): Result = withContext(Dispatchers.IO) {
        val perSongPaths: List<Pair<Song, String>> = if (targets.isNotEmpty()) {
            targets
                .filter { it.isTagWritable() }
                .mapNotNull { offering ->
                    val path = resolveWritablePath(offering.song) ?: return@mapNotNull null
                    offering.song to path
                }
        } else {
            album.songs.mapNotNull { s ->
                val path = resolveWritablePath(s) ?: return@mapNotNull null
                s to path
            }
        }

        if (perSongPaths.isEmpty()) {
            return@withContext Result(
                0,
                1,
                "No writable source selected (Jellyfin / Subsonic are read-only)"
            )
        }

        var ok = 0
        var failed = 0
        val scanned = mutableListOf<String>()
        val coverFile = edit.coverBytes?.let { bytes ->
            val dir = perSongPaths.firstOrNull()?.second?.let { File(it).parentFile }
            if (dir != null && dir.isDirectory) {
                val out = File(dir, "cover.jpg")
                try {
                    FileOutputStream(out).use { it.write(bytes) }
                    scanned += out.absolutePath
                    out
                } catch (e: Exception) {
                    Log.w(TAG, "cover.jpg write failed", e)
                    null
                }
            } else null
        }

        for ((song, path) in perSongPaths) {
            try {
                val file = File(path)
                if (!file.canWrite()) runCatching { file.setWritable(true) }
                writeAlbumTags(file, edit, coverFile)
                updateMediaStoreAlbumFields(song, edit)
                scanned += path
                ok++
            } catch (e: Exception) {
                Log.e(TAG, "saveAlbum track failed path=$path", e)
                failed++
            }
        }
        if (scanned.isNotEmpty()) scanPaths(scanned)
        if (ok > 0) libraryIndex.refresh()
        Result(
            ok = ok,
            failed = failed,
            message = when {
                failed == 0 -> "Saved $ok tracks"
                ok == 0 -> "Could not write tags — check storage permission"
                else -> "Saved $ok, failed $failed"
            }
        )
    }

    /**
     * Resolve concrete filesystem paths we are allowed to write.
     * Skips non-writable source types even if a path somehow exists.
     */
    private fun resolveWriteTargets(
        song: Song,
        targets: List<SourceOffering>
    ): List<String> {
        if (targets.isNotEmpty()) {
            return targets
                .filter { it.isTagWritable() }
                .mapNotNull { resolveWritablePath(it.song) }
                .distinct()
        }
        // Legacy single-source local path
        return listOfNotNull(resolveWritablePath(song))
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
        if (coverFile != null && coverFile.isFile) {
            try {
                tag.deleteArtworkField()
                val art = ArtworkFactory.createArtworkFromFile(coverFile)
                tag.setField(art)
            } catch (e: Exception) {
                Log.w(TAG, "embedded art failed for ${file.name}", e)
            }
        } else if (edit.coverBytes != null) {
            try {
                val tmp = File.createTempFile("yp_cover_", ".jpg", context.cacheDir)
                tmp.writeBytes(edit.coverBytes)
                tag.deleteArtworkField()
                tag.setField(ArtworkFactory.createArtworkFromFile(tmp))
                tmp.delete()
            } catch (e: Exception) {
                Log.w(TAG, "embedded art from bytes failed", e)
            }
        }
        audio.commit()
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

    fun resolveWritablePath(song: Song): String? {
        song.path?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile) return f.absolutePath
        }
        return try {
            context.contentResolver.query(
                song.contentUri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) c.getString(idx)?.takeIf { File(it).isFile }
                    else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "DATA query failed for ${song.contentUri}", e)
            null
        }
    }

    private fun updateMediaStoreSong(song: Song, edit: SongEdit) {
        val values = ContentValues().apply {
            edit.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
            edit.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            edit.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
        }
        if (values.size() == 0) return
        runCatching {
            context.contentResolver.update(song.contentUri, values, null, null)
        }.onFailure { Log.w(TAG, "MediaStore song update failed", it) }
    }

    private fun updateMediaStoreAlbumFields(song: Song, edit: AlbumEdit) {
        val values = ContentValues().apply {
            edit.albumName?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            edit.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
            edit.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
        }
        if (values.size() == 0) return
        runCatching {
            context.contentResolver.update(song.contentUri, values, null, null)
        }.onFailure { Log.w(TAG, "MediaStore album update failed", it) }
    }

    private fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(
            context,
            paths.toTypedArray(),
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
            Log.e(TAG, "readImageBytes failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "MetadataEdit"

        /** Convenience for UI: single local offering when multi-source list is not supplied. */
        fun localOffering(song: Song): SourceOffering =
            SourceOffering(
                sourceType = SourceType.LOCAL,
                sourceId = null,
                sourceName = "Local files",
                song = song
            )
    }
}
