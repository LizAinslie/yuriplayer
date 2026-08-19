package capital.yuri.yuriplayer.data.source

import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Minimal Jellyfin REST client (Emby-compatible headers).
 * Auth → AccessToken; browse Audio items; build stream URLs for ExoPlayer.
 */
class JellyfinClient(
    private val http: HttpClient,
    private val json: Json
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
        val response = http.post("$root/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            header("X-Emby-Authorization", authHeader(token = null))
            setBody(
                AuthRequest(
                    Username = username,
                    Pw = password
                )
            )
        }
        if (!response.status.isSuccess()) {
            error("Jellyfin auth failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
        }
        val body = json.decodeFromString<AuthResponse>(response.bodyAsText())
        val token = body.AccessToken ?: error("No AccessToken in auth response")
        val userId = body.User?.Id ?: error("No User.Id in auth response")
        Session(
            baseUrl = root,
            userId = userId,
            accessToken = token,
            serverName = body.ServerName
        )
    }.onFailure { Log.w(TAG, "authenticate failed", it) }

    /**
     * Lists audio items the user can play. Paged; returns up to [limit] tracks.
     */
    suspend fun listAudioItems(session: Session, limit: Int = 10_000): Result<List<Song>> =
        runCatching {
            val response = http.get("${session.baseUrl}/Users/${session.userId}/Items") {
                header("X-Emby-Token", session.accessToken)
                header("X-Emby-Authorization", authHeader(session.accessToken))
                parameter("IncludeItemTypes", "Audio")
                parameter("Recursive", true)
                parameter("Fields", "Path,MediaSources,AlbumArtist,Album,Artists,IndexNumber,ParentIndexNumber,ProductionYear,RunTimeTicks,ImageTags")
                parameter("SortBy", "Album,IndexNumber")
                parameter("SortOrder", "Ascending")
                parameter("Limit", limit)
            }
            if (!response.status.isSuccess()) {
                error("Items failed: HTTP ${response.status.value}")
            }
            val page = json.decodeFromString<ItemsResponse>(response.bodyAsText())
            page.Items.orEmpty().mapNotNull { it.toSong(session) }
        }.onFailure { Log.w(TAG, "listAudioItems failed", it) }

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

    private fun authHeader(token: String?): String {
        val deviceId = DEVICE_ID
        val parts = buildList {
            add("MediaBrowser Client=\"YuriPlayer\"")
            add("Device=\"Android\"")
            add("DeviceId=\"$deviceId\"")
            add("Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) add("Token=\"$token\"")
        }
        return parts.joinToString(", ")
    }

    @Serializable
    private data class AuthRequest(
        val Username: String,
        val Pw: String
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
        private val DEVICE_ID: String = UUID.randomUUID().toString()
    }
}
