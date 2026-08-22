package capital.yuri.yuriplayer.network

import capital.yuri.yuriplayer.core.log.yuriLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.BinaryLogBodyFilter
import io.ktor.client.plugins.logging.BodyFilterResult
import io.ktor.client.plugins.logging.LogBodyFilter
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module

const val IMAGES_CLIENT = "ktor.images"

val httpModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }

    single {
        val json: Json = get()
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 45_000
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        yuriLog("Ktor").d { redactSecrets(omitBinaryDump(message)) }
                    }
                }
                // BODY so auth failures are diagnosable; secrets stripped in [redactSecrets].
                level = LogLevel.BODY
                bodyFilter = SkipBinaryBodyFilter
            }
            defaultRequest {
                header(
                    "User-Agent",
                    "YuriPlayer/1.0 (https://github.com/LizAinslie/yuriplayer)"
                )
            }
        }
    }

    /** Coil-only client: no BODY logging, isolated from API request jobs. */
    single(named(IMAGES_CLIENT)) {
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 45_000
            }
            defaultRequest {
                header(
                    "User-Agent",
                    "YuriPlayer/1.0 (https://github.com/LizAinslie/yuriplayer)"
                )
            }
        }
    }
}

/**
 * Skip images / audio / other binary payloads. JSON and text still log at BODY.
 */
private object SkipBinaryBodyFilter : LogBodyFilter {
    override suspend fun filterRequest(
        url: Url,
        contentLength: Long?,
        contentType: ContentType?,
        headers: Headers,
        body: ByteReadChannel
    ): BodyFilterResult =
        BinaryLogBodyFilter.filterRequest(url, contentLength, contentType, headers, body)

    override suspend fun filterResponse(
        url: Url,
        contentLength: Long?,
        contentType: ContentType?,
        headers: Headers,
        body: ByteReadChannel
    ): BodyFilterResult {
        if (shouldOmitBody(url, contentType, contentLength)) {
            return BodyFilterResult.Skip(
                reason = binaryResponseLabel(contentType, contentLength),
                byteSize = contentLength
            )
        }
        return BinaryLogBodyFilter.filterResponse(url, contentLength, contentType, headers, body)
    }
}

internal fun shouldOmitBody(
    url: Url,
    contentType: ContentType?,
    contentLength: Long?
): Boolean {
    if (isBinaryContentType(contentType)) return true
    val path = url.encodedPath.lowercase()
    if (BINARY_PATH.containsMatchIn(path)) return true
    val charset = contentType?.charset()
    if (contentLength != null && contentLength > TEXT_BODY_LOG_LIMIT && charset == null &&
        !isTextContentType(contentType)
    ) {
        return true
    }
    return false
}

private fun isTextContentType(type: ContentType?): Boolean {
    if (type == null) return false
    val top = type.contentType.lowercase()
    val sub = type.contentSubtype.lowercase()
    if (top == "text") return true
    if (top == "application" && sub in TEXT_APP_SUBTYPES) return true
    return type.charset() != null
}

private fun isBinaryContentType(type: ContentType?): Boolean {
    if (type == null) return false
    val top = type.contentType.lowercase()
    val sub = type.contentSubtype.lowercase()
    if (top in BINARY_TOP_TYPES) return true
    if (top == "application" && (sub in BINARY_APP_SUBTYPES || sub.endsWith("+zip"))) return true
    return false
}

/**
 * Strip credentials from Ktor log lines before they hit logcat.
 * Covers JSON bodies (Pw/Password), query tokens, and Authorization headers.
 */
internal fun redactSecrets(message: String): String {
    var out = message
    // JSON password fields (Jellyfin AuthenticateByName, etc.)
    out = JSON_SECRET_FIELDS.replace(out) { m ->
        "\"${m.groupValues[1]}\":\"***\""
    }
    // Subsonic token query params and generic secrets
    out = QUERY_SECRETS.replace(out) { m ->
        "${m.groupValues[1]}=***"
    }
    // Authorization / X-Emby-* header values (may embed Token="...")
    out = HEADER_SECRETS.replace(out) { m ->
        "${m.groupValues[1]}: ***"
    }
    out = EMBEDDED_TOKEN.replace(out, "Token=\"***\"")
    return out
}

internal fun binaryResponseLabel(contentType: ContentType?, contentLength: Long?): String {
    val type = contentType?.withoutParameters()?.toString()
    val size = contentLength?.takeIf { it > 0 }?.let { "$it bytes" }
    val extra = listOfNotNull(type, size).joinToString(" ")
    return if (extra.isEmpty()) "[Binary response]" else "[Binary response] $extra"
}

/** Last-resort: if a JPEG/PNG dump still made it into the log line, drop the payload. */
internal fun omitBinaryDump(message: String): String {
    if (!looksLikeBinaryDump(message)) return message
    val cut = message.indexOf("\nBODY")
        .takeIf { it >= 0 }
        ?: message.indexOf("\nBODY START")
        ?: message.indexOf("\nBODY Content")
    val head = if (cut != null && cut > 0) message.substring(0, cut) else message.take(400)
    return "$head\n[Binary response]"
}

private fun looksLikeBinaryDump(message: String): Boolean {
    if (message.length < 512) return false
    if (message.contains("\u0089PNG") || message.contains("JFIF") || message.contains("WEBP")) {
        return true
    }
    val sample = if (message.length > 4000) message.substring(message.length - 2000) else message
    var weird = 0
    for (c in sample) {
        val n = c.code
        if (n < 9 || (n in 14..31) || n == 127 || n > 255) weird++
        else if (n in 128..255) weird++
    }
    return weird * 8 > sample.length
}

private const val TEXT_BODY_LOG_LIMIT = 512 * 1024L

private val BINARY_TOP_TYPES = setOf("image", "audio", "video", "font", "multipart")
private val BINARY_APP_SUBTYPES = setOf(
    "octet-stream", "zip", "gzip", "x-gzip", "pdf", "protobuf",
    "x-protobuf", "grpc", "wasm", "ogg", "msword", "vnd.ms-excel"
)
private val TEXT_APP_SUBTYPES = setOf(
    "json", "xml", "javascript", "x-www-form-urlencoded",
    "problem+json", "ld+json", "graphql+json", "xhtml+xml", "soap+xml"
)
private val BINARY_PATH = Regex(
    """(?i)(\.jpe?g|\.png|\.gif|\.webp|\.bmp|\.ico|\.svg|\.avif|\.mp3|\.flac|\.ogg|\.m4a|\.mp4|/images/|/cover|/artwork)"""
)

private val JSON_SECRET_FIELDS = Regex(
    "\"(Pw|Password|password|secret|token|AccessToken|api_key|apiKey)\"\\s*:\\s*\"[^\"]*\"",
    RegexOption.IGNORE_CASE
)
private val QUERY_SECRETS = Regex(
    "(?i)(p|pw|password|t|token|s|secret|api_key|apiKey|AccessToken)=[^&\\s\"']+"
)
private val HEADER_SECRETS = Regex(
    "(?im)^(Authorization|X-Emby-Authorization|X-Emby-Token|X-MediaBrowser-Token)\\s*:\\s*.+$"
)
private val EMBEDDED_TOKEN = Regex(
    "Token=\"[^\"]*\"",
    RegexOption.IGNORE_CASE
)
