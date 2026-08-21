package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LibraryFaviconStore(
    context: Context,
    private val http: HttpClient
) {
    private val dir = File(context.applicationContext.filesDir, "library-favicons").apply { mkdirs() }

    fun cachedFile(instanceId: Long): File? {
        val f = File(dir, "$instanceId.bin")
        return f.takeIf { it.isFile && it.length() > 32 }
    }

    suspend fun ensure(instance: SourceInstanceEntity): File? = withContext(Dispatchers.IO) {
        val existing = cachedFile(instance.id)
        if (existing != null) return@withContext existing
        val base = instance.baseUrl?.trimEnd('/') ?: return@withContext null
        val candidates = listOf(
            "$base/web/favicon.ico",
            "$base/favicon.ico",
            "$base/favicon.png"
        )
        for (url in candidates) {
            val bytes = runCatching {
                val resp = http.get(url)
                if (!resp.status.isSuccess()) return@runCatching null
                val body = resp.bodyAsBytes()
                if (body.size < 32 || looksLikeHtml(body)) null else body
            }.getOrNull() ?: continue
            val dest = File(dir, "${instance.id}.bin")
            dest.writeBytes(bytes)
            Log.i(TAG, "favicon ${instance.name} ← $url ${bytes.size}B")
            return@withContext dest
        }
        null
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1).lowercase()
        return head.contains("<html") || head.contains("<!doctype")
    }

    companion object {
        private const val TAG = "LibraryFavicon"
    }
}
