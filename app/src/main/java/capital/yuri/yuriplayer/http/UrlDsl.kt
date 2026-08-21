package capital.yuri.yuriplayer.http

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments

/**
 * Thin Ktor [URLBuilder] wrapper. Path + query, values encoded by Ktor.
 *
 * ```
 * url("https://host/music") {
 *     path("rest", "stream.view")
 *     param("id", trackId)
 *     param("format", "raw")
 * }
 * ```
 */
fun url(base: String, block: UrlScope.() -> Unit = {}): String =
    UrlScope(URLBuilder(base.trim())).apply(block).build()

class UrlScope internal constructor(private val builder: URLBuilder) {
    fun path(vararg segments: String, encodeSlash: Boolean = false) {
        val parts = segments.filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            builder.appendPathSegments(*parts.toTypedArray(), encodeSlash = encodeSlash)
        }
    }

    fun param(name: String, value: Any?) {
        if (value == null) return
        val text = value.toString()
        if (text.isEmpty()) return
        builder.parameters.append(name, text)
    }

    internal fun build(): String = builder.buildString()
}
