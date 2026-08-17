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
                        android.util.Log.d("Ktor", message)
                    }
                }
                level = LogLevel.INFO
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
