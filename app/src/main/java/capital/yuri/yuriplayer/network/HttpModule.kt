package capital.yuri.yuriplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

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
        HttpClient(Android) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        android.util.Log.d("Ktor", redactSecrets(message))
                    }
                }
                // BODY so auth failures are diagnosable; secrets stripped in [redactSecrets].
                level = LogLevel.BODY
            }
            defaultRequest {
                header(
                    "User-Agent",
                    "YuriPlayer/1.0 (https://github.com/LizAinslie/yuriplayer)"
                )
            }
            engine {
                connectTimeout = 15_000
                socketTimeout = 30_000
            }
        }
    }
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
