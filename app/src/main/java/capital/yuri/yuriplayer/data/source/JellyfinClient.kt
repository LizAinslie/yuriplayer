package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Minimal Jellyfin REST client (Emby-compatible headers).
 * Auth → AccessToken; browse Audio items; build stream URLs for ExoPlayer.
 */
class JellyfinClient(
    private val http: HttpClient,
    private val json: Json,
    private val deviceId: String
) {
    data class Session(
        val baseUrl: String,
        val userId: String,
        val accessToken: String,
        val serverName: String? = null
    )

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String
    ): Result<Session> = runCatching {
        val root = SourceInstanceRepository.normalizeBaseUrl(baseUrl)
        val user = username.trim()
        require(user.isNotEmpty()) { "Username is empty" }

        Log.i(TAG, "authenticate → POST $root/Users/AuthenticateByName user=$user deviceId=${deviceId.take(8)}…")

        val response = http.post("$root/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            // Jellyfin 10.8+ prefers Authorization; older builds still read X-Emby-Authorization.
            val ident = clientIdentification(token = null)
            header("Authorization", ident)
            header("X-Emby-Authorization", ident)
            setBody(
                AuthRequest(
                    Username = user,
                    // Jellyfin accepts either; some reverse proxies / older builds prefer Password.
                    Pw = password,
                    Password = password
                )
            )
        }

        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = jellyfinErrorMessage(raw) ?: raw.take(240)
            Log.w(TAG, "auth HTTP ${response.status.value}: $detail")
            error("Jellyfin auth failed: HTTP ${response.status.value} — $detail")
        }

        val body = json.decodeFromString<AuthResponse>(raw)
        val token = body.AccessToken ?: error("No AccessToken in auth response")
        val userId = body.User?.Id ?: error("No User.Id in auth response")
        Log.i(TAG, "auth ok server=${body.ServerName} userId=${userId.take(8)}…")
        Session(
            baseUrl = root,
            userId = userId,
            accessToken = token,
            serverName = body.ServerName
        )
    }.onFailure { Log.w(TAG, "authenticate failed: ${it.message}") }

    /**
     * Lists audio items the user can play. Paged; returns up to [limit] tracks.
     */
    suspend fun listAudioItems(session: Session, limit: Int = 10_000): Result<List<Song>> =
        runCatching {
            val ident = clientIdentification(session.accessToken)
            val response = http.get("${session.baseUrl}/Users/${session.userId}/Items") {
                header("Authorization", ident)
                header("X-Emby-Authorization", ident)
                header("X-Emby-Token", session.accessToken)
                parameter("IncludeItemTypes", "Audio")
                parameter("Recursive", true)
                parameter(
                    "Fields",
                    "Path,MediaSources,AlbumArtist,Album,Artists,IndexNumber,ParentIndexNumber,ProductionYear,RunTimeTicks,ImageTags"
                )
                parameter("SortBy", "Album,IndexNumber")
                parameter("SortOrder", "Ascending")
                parameter("Limit", limit)
            }
            if (!response.status.isSuccess()) {
                val raw = response.bodyAsText()
                error("Items failed: HTTP ${response.status.value} — ${jellyfinErrorMessage(raw) ?: raw.take(160)}")
            }
            val page = json.decodeFromString<ItemsResponse>(response.bodyAsText())
            page.Items.orEmpty().mapNotNull { it.toSong(session) }
        }.onFailure { Log.w(TAG, "listAudioItems failed: ${it.message}") }

    fun streamUrl(session: Session, itemId: String): String =
        "${session.baseUrl}/Audio/$itemId/stream?static=true&api_key=${session.accessToken}"

    fun primaryImageUrl(session: Session, itemId: String, maxWidth: Int = 512): String =
        "${session.baseUrl}/Items/$itemId/Images/Primary?maxWidth=$maxWidth&api_key=${session.accessToken}"

    private fun JellyfinItem.toSong(session: Session): Song? {
        val id = Id ?: return null
        val stream = streamUrl(session, id)
        val art = if (ImageTags?.Primary != null) {
            Uri.parse(primaryImageUrl(session, id))
        } else null
        val durationMs = RunTimeTicks?.let { ticks -> ticks / 10_000L }?.takeIf { it > 0 }
        val artists = Artists?.filter { it.isNotBlank() }.orEmpty()
        val albumArtists = AlbumArtist?.takeIf { it.isNotBlank() }
            ?: AlbumArtists?.firstOrNull()?.takeIf { it.isNotBlank() }
        return Song(
            id = id.hashCode().toLong(),
            title = Name,
            artist = artists.firstOrNull() ?: albumArtists,
            albumArtist = albumArtists,
            album = Album,
            durationMs = durationMs,
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = IndexNumber,
            discNumber = ParentIndexNumber,
            year = ProductionYear,
            path = Path,
            mimeType = null
        )
    }

    /**
     * Jellyfin client identification string.
     * Format: MediaBrowser Client="…", Device="…", DeviceId="…", Version="…"[, Token="…"]
     */
    private fun clientIdentification(token: String?): String {
        val parts = buildList {
            add("MediaBrowser Client=\"YuriPlayer\"")
            add("Device=\"Android\"")
            add("DeviceId=\"$deviceId\"")
            add("Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) add("Token=\"$token\"")
        }
        return parts.joinToString(", ")
    }

    private fun jellyfinErrorMessage(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val err = json.decodeFromString<JellyfinError>(raw)
            listOfNotNull(err.Message, err.error, err.title)
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }

    @Serializable
    private data class AuthRequest(
        val Username: String,
        val Pw: String,
        val Password: String
    )

    @Serializable
    private data class AuthResponse(
        val AccessToken: String? = null,
        val ServerName: String? = null,
        val User: JellyfinUser? = null
    )

    @Serializable
    private data class JellyfinUser(
        val Id: String? = null,
        val Name: String? = null
    )

    @Serializable
    private data class JellyfinError(
        val Message: String? = null,
        val error: String? = null,
        val title: String? = null
    )

    @Serializable
    private data class ItemsResponse(
        val Items: List<JellyfinItem>? = null,
        val TotalRecordCount: Int? = null
    )

    @Serializable
    private data class JellyfinItem(
        val Id: String? = null,
        val Name: String? = null,
        val Album: String? = null,
        val AlbumArtist: String? = null,
        val AlbumArtists: List<String>? = null,
        val Artists: List<String>? = null,
        val IndexNumber: Int? = null,
        val ParentIndexNumber: Int? = null,
        val ProductionYear: Int? = null,
        val RunTimeTicks: Long? = null,
        val Path: String? = null,
        val ImageTags: ImageTags? = null
    )

    @Serializable
    private data class ImageTags(
        val Primary: String? = null
    )

    companion object {
        private const val TAG = "JellyfinClient"
        private const val PREFS = "jellyfin_client"
        private const val KEY_DEVICE_ID = "device_id"

        fun stableDeviceId(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }
    }
}
