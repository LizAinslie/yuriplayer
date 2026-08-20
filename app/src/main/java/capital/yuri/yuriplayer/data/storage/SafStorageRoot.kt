package capital.yuri.yuriplayer.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * [StorageRoot] over a persistable SAF tree URI.
 *
 * Requires the caller already took persistable read (and write when mutating)
 * URI permission. No extra grants are requested here.
 */
class SafStorageRoot(
    private val context: Context,
    treeUri: Uri,
    override val displayName: String = treeUri.lastPathSegment ?: "SAF tree"
) : StorageRoot {

    override val id: String = treeUri.toString()

    private val tree: DocumentFile? =
        DocumentFile.fromTreeUri(context.applicationContext, treeUri)

    override val capabilities: StorageCapabilities = StorageCapabilities(
        canRead = true,
        canWrite = true,
        canMkdir = true,
        canDelete = true,
        canMove = true,
        persistent = true
    )

    override suspend fun list(path: String): List<StorageEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(path) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val childPath = join(path, name)
            StorageEntry(
                path = childPath,
                name = name,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isFile) child.length().takeIf { it >= 0 } else null,
                mimeType = child.type,
                lastModifiedMs = child.lastModified().takeIf { it > 0 },
                nativeId = child.uri.toString()
            )
        }
    }

    override suspend fun metadata(path: String): StorageEntry? = withContext(Dispatchers.IO) {
        val doc = resolve(path) ?: return@withContext null
        val name = doc.name ?: path.substringAfterLast('/').ifBlank { "root" }
        StorageEntry(
            path = path.trim('/'),
            name = name,
            isDirectory = doc.isDirectory,
            sizeBytes = if (doc.isFile) doc.length().takeIf { it >= 0 } else null,
            mimeType = doc.type,
            lastModifiedMs = doc.lastModified().takeIf { it > 0 },
            nativeId = doc.uri.toString()
        )
    }

    override suspend fun openRead(path: String): InputStream = withContext(Dispatchers.IO) {
        val doc = resolve(path) ?: error("Not found: $path")
        context.contentResolver.openInputStream(doc.uri)
            ?: error("Cannot open read: $path")
    }

    override suspend fun openWrite(path: String, overwrite: Boolean): OutputStream =
        withContext(Dispatchers.IO) {
            val existing = resolve(path)
            val target = when {
                existing != null && existing.isFile -> {
                    if (!overwrite) error("Already exists: $path")
                    existing
                }
                else -> {
                    val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
                    val name = path.substringAfterLast('/')
                    require(name.isNotBlank()) { "Invalid path: $path" }
                    val parent = ensureDir(parentPath)
                        ?: error("Cannot create parent for $path")
                    val mime = guessMime(name)
                    parent.createFile(mime, name.removeSuffix(".${name.substringAfterLast('.')}"))
                        ?: parent.findFile(name)
                        ?: error("Cannot create file: $path")
                }
            }
            context.contentResolver.openOutputStream(target.uri, if (overwrite) "wt" else "wa")
                ?: error("Cannot open write: $path")
        }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        ensureDir(path) != null
    }

    override suspend fun delete(path: String, recursive: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val doc = resolve(path) ?: return@withContext false
            if (doc.isDirectory && !recursive) {
                val kids = doc.listFiles()
                if (kids.isNotEmpty()) return@withContext false
            }
            doc.delete()
        }

    override suspend fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val src = resolve(fromPath) ?: return@withContext false
            val destExisting = resolve(toPath)
            if (destExisting != null) {
                if (!overwrite) return@withContext false
                if (!destExisting.delete()) return@withContext false
            }
            val parentPath = toPath.substringBeforeLast('/', missingDelimiterValue = "")
            val newName = toPath.substringAfterLast('/')
            val parent = ensureDir(parentPath) ?: return@withContext false
            // Same-parent rename is cheap; otherwise copy+delete via streams.
            val srcParent = src.parentFile
            if (srcParent != null && srcParent.uri == parent.uri) {
                return@withContext src.renameTo(newName)
            }
            if (src.isDirectory) return@withContext false // directory cross-move: not yet
            val mime = src.type ?: guessMime(newName)
            val dest = parent.createFile(mime, newName.substringBeforeLast('.'))
                ?: return@withContext false
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                } ?: return@withContext false
            } ?: return@withContext false
            src.delete()
        }

    private fun resolve(path: String): DocumentFile? {
        val root = tree ?: return null
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return root
        var cur: DocumentFile = root
        for (segment in trimmed.split('/')) {
            if (segment.isEmpty()) continue
            cur = cur.findFile(segment) ?: return null
        }
        return cur
    }

    private fun ensureDir(path: String): DocumentFile? {
        val root = tree ?: return null
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return root
        var cur: DocumentFile = root
        for (segment in trimmed.split('/')) {
            if (segment.isEmpty()) continue
            val next = cur.findFile(segment)
            cur = when {
                next == null -> cur.createDirectory(segment) ?: return null
                next.isDirectory -> next
                else -> return null
            }
        }
        return cur
    }

    private fun join(parent: String, name: String): String {
        val p = parent.trim().trim('/')
        return if (p.isEmpty()) name else "$p/$name"
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    companion object {
        fun fromTreeUri(context: Context, treeUriString: String, label: String? = null): SafStorageRoot? {
            val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
            if (uri.scheme != "content") return null
            return SafStorageRoot(
                context = context.applicationContext,
                treeUri = uri,
                displayName = label ?: uri.lastPathSegment ?: "Local folder"
            )
        }
    }
}
