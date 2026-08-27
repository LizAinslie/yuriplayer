package capital.yuri.yuriplayer.data.organize

import android.content.Context
import android.net.Uri
import capital.yuri.yuriplayer.core.log.yuriLog
import androidx.documentfile.provider.DocumentFile
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Moves local SAF (or future folder-like) tracks into a user template under
 * the same granted root. Never leaves the tree.
 */
class LibraryOrganizeService(
    private val context: Context,
    private val library: LibraryIndex,
    private val layoutPrefs: OrganizeLayoutPrefs
) {

    data class PlannedMove(
        val song: Song,
        val fromLabel: String,
        val toRelative: String,
        val alreadyOk: Boolean,
        val skipReason: String? = null
    )

    data class Plan(
        val rootKey: String,
        val moves: List<PlannedMove>,
        val moveCount: Int,
        val skipCount: Int
    )

    data class ApplyResult(
        val moved: Int,
        val skipped: Int,
        val failed: Int,
        val message: String
    )

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun layoutFor(rootKey: String): OrganizeLayout = layoutPrefs.get(rootKey)

    fun saveLayout(layout: OrganizeLayout) = layoutPrefs.set(layout)

    suspend fun plan(rootKey: String, songs: List<Song>): Plan = withContext(Dispatchers.IO) {
        val layout = layoutPrefs.get(rootKey)
        val rootUri = runCatching { Uri.parse(rootKey) }.getOrNull()
        val root = rootUri?.let { DocumentFile.fromTreeUri(context, it) }

        val planned = mutableListOf<PlannedMove>()
        for (song in songs) {
            if (!songBelongsToRoot(song, rootKey)) continue
            val rel = PathTemplate.relativePathFor(layout, song)
            val fromLabel = song.path?.substringAfterLast('/')
                ?: Uri.parse(song.contentUri).lastPathSegment
                ?: song.displayTitle
            val currentRel = relativeUnderRoot(song, rootKey)
            val already = currentRel != null && currentRel.equals(rel, ignoreCase = true)
            planned += PlannedMove(
                song = song,
                fromLabel = fromLabel,
                toRelative = rel,
                alreadyOk = already,
                skipReason = when {
                    already -> "already in place"
                    root == null -> "invalid SAF tree"
                    else -> null
                }
            )
        }
        val moves = planned.filter { !it.alreadyOk && it.skipReason == null }
        Plan(
            rootKey = rootKey,
            moves = planned,
            moveCount = moves.size,
            skipCount = planned.size - moves.size
        )
    }

    suspend fun apply(rootKey: String, songs: List<Song>): ApplyResult = withContext(Dispatchers.IO) {
        if (_busy.value) return@withContext ApplyResult(0, 0, 0, "Organize already running")
        _busy.value = true
        _status.value = "Organizing…"
        try {
            val layout = layoutPrefs.get(rootKey)
            if (!layout.enabled) {
                return@withContext ApplyResult(0, 0, 0, "Organize disabled for this root")
            }
            val rootUri = Uri.parse(rootKey)
            val root = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@withContext ApplyResult(0, 0, 0, "Cannot open SAF tree")

            val plan = plan(rootKey, songs)
            var moved = 0
            var skipped = plan.skipCount
            var failed = 0

            for (item in plan.moves) {
                if (item.alreadyOk || item.skipReason != null) continue
                _status.value = "Moving ${item.fromLabel}…"
                val ok = moveSong(root, item.song, item.toRelative, layout.collision)
                if (ok) moved++ else failed++
            }

            if (moved > 0) {
                _status.value = "Rescanning…"
                library.refresh()
            }
            val msg = "Moved $moved · skipped $skipped · failed $failed"
            _status.value = msg
            ApplyResult(moved, skipped, failed, msg)
        } catch (e: Exception) {
            log.e(e) { "apply failed" }
            _status.value = e.message
            ApplyResult(0, 0, 1, e.message ?: "Organize failed")
        } finally {
            _busy.value = false
        }
    }

    private fun songBelongsToRoot(song: Song, rootKey: String): Boolean {
        val uri = song.contentUri
        if (uri.startsWith(rootKey) || rootKey in uri) return true
        val path = song.path.orEmpty()
        // Document path form: /tree/primary:Music/document/...
        val treeHint = runCatching {
            Uri.parse(rootKey).lastPathSegment?.substringAfterLast(':')
        }.getOrNull()
        if (!treeHint.isNullOrBlank() && path.contains(treeHint)) return true
        return false
    }

    private fun relativeUnderRoot(song: Song, rootKey: String): String? {
        val decoded = Uri.decode(song.contentUri)
        val marker = "/document/"
        val idx = decoded.indexOf(marker)
        if (idx < 0) return null
        val doc = decoded.substring(idx + marker.length)
        // primary:Music/Artist/Album/track.flac → strip volume prefix
        val afterColon = doc.substringAfter(':', doc)
        val rootLabel = Uri.parse(rootKey).lastPathSegment?.substringAfterLast(':').orEmpty()
        return if (rootLabel.isNotEmpty() && afterColon.startsWith(rootLabel)) {
            afterColon.removePrefix(rootLabel).trimStart('/')
        } else afterColon
    }

    private fun moveSong(
        root: DocumentFile,
        song: Song,
        relative: String,
        collision: OrganizeLayout.CollisionPolicy
    ): Boolean {
        return try {
            val src = DocumentFile.fromSingleUri(context, Uri.parse(song.contentUri)) ?: return false
            if (!src.isFile) return false

            val segments = relative.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return false
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)

            var dir = root
            for (seg in dirSegments) {
                val existing = dir.findFile(seg)
                dir = when {
                    existing != null && existing.isDirectory -> existing
                    existing != null && existing.isFile -> {
                        log.w { "path collision with file $seg" }
                        return false
                    }
                    else -> dir.createDirectory(seg) ?: return false
                }
            }

            var destName = fileName
            val existingDest = dir.findFile(destName)
            if (existingDest != null && existingDest.uri != src.uri) {
                when (collision) {
                    OrganizeLayout.CollisionPolicy.SKIP -> return false
                    OrganizeLayout.CollisionPolicy.OVERWRITE -> {
                        existingDest.delete()
                    }
                    OrganizeLayout.CollisionPolicy.SUFFIX -> {
                        destName = uniqueName(dir, fileName)
                    }
                }
            }

            // Same parent: rename
            if (src.parentFile?.uri == dir.uri) {
                return src.renameTo(destName)
            }

            // Cross-folder: copy then delete (SAF has no atomic move)
            val mime = src.type ?: "audio/*"
            val dest = dir.createFile(mime, destName.substringBeforeLast('.')) ?: return false
            // createFile may append extension; ensure we overwrite content
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                } ?: return false
            } ?: return false

            if (dest.length() <= 0L) {
                dest.delete()
                return false
            }
            src.delete()
            true
        } catch (e: Exception) {
            log.w { "move failed ${song.songKey}: ${e.message}" }
            false
        }
    }

    private fun uniqueName(dir: DocumentFile, fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var n = 2
        while (n < 1000) {
            val candidate = if (ext.isNotEmpty()) "$base ($n).$ext" else "$base ($n)"
            if (dir.findFile(candidate) == null) return candidate
            n++
        }
        return "$base (${System.currentTimeMillis()}).$ext"
    }

    companion object {
        private val log = yuriLog("LibraryOrganize")
    }
}
