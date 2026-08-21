package capital.yuri.yuriplayer.core.source

import capital.yuri.yuriplayer.core.http.normalizeBaseUrl
import capital.yuri.yuriplayer.core.http.url
import capital.yuri.yuriplayer.core.library.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class JellyfinCatalog(
    private val http: HttpClient,
    private val deviceId: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun authenticate(account: RemoteAccount): Result<RemoteAccount> = runCatching {
        val root = normalizeBaseUrl(account.baseUrl)
        val response = http.post("$root/Users/AuthenticateByName") {
            header("X-Emby-Authorization", authHeader(token = null))
            setBody(
                TextContent(
                    json.encodeToString(
                        AuthRequest.serializer(),
                        AuthRequest(account.username.trim(), account.secret)
                    ),
                    ContentType.Application.Json
                )
            )
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) error("Invalid username or password")
            error("Jellyfin HTTP ${response.status.value}")
        }
        val body = json.decodeFromString<AuthResponse>(response.bodyAsText())
        val token = body.accessToken ?: error("No access token")
        val userId = body.user?.id ?: error("No user id")
        account.copy(
            baseUrl = root,
            accessToken = token,
            userId = userId,
            name = account.name.ifBlank { body.user?.name ?: "Jellyfin" }
        )
    }

    suspend fun audioCount(account: RemoteAccount): Result<Int> = runCatching {
        val token = account.accessToken ?: error("Not signed in")
        val userId = account.userId ?: error("Not signed in")
        val root = normalizeBaseUrl(account.baseUrl)
        val requestUrl = url(root) {
            path("Users", userId, "Items")
            param("IncludeItemTypes", "Audio")
            param("Recursive", "true")
            param("Limit", 0)
        }
        val response = http.get(requestUrl) {
            header("X-Emby-Authorization", authHeader(token))
            header("X-Emby-Token", token)
        }
        if (!response.status.isSuccess()) error("Items HTTP ${response.status.value}")
        json.decodeFromString<ItemsResponse>(response.bodyAsText()).total ?: 0
    }

    suspend fun listTracks(
        account: RemoteAccount,
        maxItems: Int = 50_000,
        sortBy: String = "Album,SortName",
        sortOrder: String = "Ascending",
        startFrom: Int = 0,
        onPage: suspend (page: List<Track>, cursor: Int, total: Int?) -> Unit = { _, _, _ -> }
    ): Result<List<Track>> =
        runCatching {
            val token = account.accessToken ?: error("Not signed in")
            val userId = account.userId ?: error("Not signed in")
            val root = normalizeBaseUrl(account.baseUrl)
            val out = ArrayList<Track>(512)
            var start = startFrom.coerceAtLeast(0)
            val pageSize = 400
            while (out.size < maxItems) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val take = minOf(pageSize, maxItems - out.size)
                val requestUrl = url(root) {
                    path("Users", userId, "Items")
                    param("IncludeItemTypes", "Audio")
                    param("Recursive", "true")
                    param("Fields", "Album,AlbumArtist,Artists,RunTimeTicks,IndexNumber,ParentIndexNumber,ProductionYear,Genres,PrimaryImageAspectRatio,Container")
                    param("EnableImageTypes", "Primary")
                    param("ImageTypeLimit", "1")
                    param("StartIndex", start)
                    param("Limit", take)
                    param("SortBy", sortBy)
                    param("SortOrder", sortOrder)
                }
                val response = http.get(requestUrl) {
                    header("X-Emby-Authorization", authHeader(token))
                    header("X-Emby-Token", token)
                }
                if (!response.status.isSuccess()) error("Items HTTP ${response.status.value}")
                val page = json.decodeFromString<ItemsResponse>(response.bodyAsText())
                val items = page.items
                if (items.isEmpty()) break
                val mapped = items.mapNotNull { toTrack(account.copy(baseUrl = root), it) }
                out += mapped
                start += items.size
                onPage(mapped, start, page.total)
                if (items.size < take) break
                val total = page.total
                if (total != null && start >= total) break
            }
            out
        }

    suspend fun searchTracks(account: RemoteAccount, query: String, limit: Int = 80): Result<List<Track>> =
        runCatching {
            val token = account.accessToken ?: error("Not signed in")
            val userId = account.userId ?: error("Not signed in")
            val root = normalizeBaseUrl(account.baseUrl)
            val requestUrl = url(root) {
                path("Users", userId, "Items")
                param("SearchTerm", query)
                param("IncludeItemTypes", "Audio")
                param("Recursive", "true")
                param("Fields", "Album,AlbumArtist,Artists,RunTimeTicks,IndexNumber,ParentIndexNumber,ProductionYear,Genres,PrimaryImageAspectRatio,Container")
                param("EnableImageTypes", "Primary")
                param("ImageTypeLimit", "1")
                param("Limit", limit)
            }
            val response = http.get(requestUrl) {
                header("X-Emby-Authorization", authHeader(token))
                header("X-Emby-Token", token)
            }
            if (!response.status.isSuccess()) error("Search HTTP ${response.status.value}")
            val page = json.decodeFromString<ItemsResponse>(response.bodyAsText())
            page.items.mapNotNull { toTrack(account.copy(baseUrl = root), it) }
        }

    suspend fun searchArtistImages(account: RemoteAccount, query: String): Result<List<capital.yuri.yuriplayer.core.artist.ArtistImageCandidate>> =
        runCatching {
            val token = account.accessToken ?: error("Not signed in")
            val userId = account.userId ?: error("Not signed in")
            val root = normalizeBaseUrl(account.baseUrl)
            val requestUrl = url(root) {
                path("Users", userId, "Items")
                param("SearchTerm", query)
                param("IncludeItemTypes", "MusicArtist")
                param("Recursive", "true")
                param("EnableImages", "true")
                param("EnableImageTypes", "Primary,Backdrop")
                param("ImageTypeLimit", "2")
                param("Limit", 8)
            }
            val response = http.get(requestUrl) {
                header("X-Emby-Authorization", authHeader(token))
                header("X-Emby-Token", token)
            }
            if (!response.status.isSuccess()) error("Search HTTP ${response.status.value}")
            val page = json.decodeFromString<ItemsResponse>(response.bodyAsText())
            val out = ArrayList<capital.yuri.yuriplayer.core.artist.ArtistImageCandidate>()
            for (item in page.items) {
                val id = item.id ?: continue
                val name = item.name ?: continue
                if (item.imageTags?.containsKey("Primary") == true) {
                    out += capital.yuri.yuriplayer.core.artist.ArtistImageCandidate(
                        url = url(root) {
                            path("Items", id, "Images", "Primary")
                            param("maxWidth", 800)
                            param("quality", 90)
                            param("api_key", token)
                        },
                        sourceId = "jellyfin",
                        label = "Jellyfin · ${account.name} · $name",
                        width = 800,
                        height = 800
                    )
                }
                if (!item.backdropTags.isNullOrEmpty() || item.imageTags?.containsKey("Backdrop") == true) {
                    out += capital.yuri.yuriplayer.core.artist.ArtistImageCandidate(
                        url = url(root) {
                            path("Items", id, "Images", "Backdrop")
                            param("maxWidth", 1920)
                            param("quality", 90)
                            param("api_key", token)
                        },
                        sourceId = "jellyfin",
                        label = "Jellyfin backdrop · ${account.name} · $name",
                        width = 1920,
                        height = 1080
                    )
                }
            }
            out
        }

    private fun toTrack(account: RemoteAccount, item: JfItem): Track? {
        val id = item.id ?: return null
        val token = account.accessToken ?: return null
        val artist = item.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: item.albumArtist
        val artId = when {
            item.imageTags?.containsKey("Primary") == true -> id
            !item.albumId.isNullOrBlank() -> item.albumId
            else -> null
        }
        val art = artId?.let {
            url(account.baseUrl) {
                path("Items", it, "Images", "Primary")
                param("maxWidth", 600)
                param("quality", 90)
                param("api_key", token)
            }
        }
        val stream = url(account.baseUrl) {
            path("Audio", id, "stream")
            param("static", true)
            param("api_key", token)
            item.container?.let { param("Container", it) }
        }
        return Track(
            id = "jellyfin:$id",
            uri = stream,
            title = item.name,
            artist = artist,
            albumArtist = item.albumArtist ?: artist,
            album = item.album,
            durationMs = item.runTimeTicks?.div(10_000L)?.takeIf { it > 0 },
            trackNumber = item.indexNumber,
            discNumber = item.parentIndexNumber,
            year = item.year,
            artworkUri = art,
            sourceId = account.id
        )
    }

    private fun authHeader(token: String?): String {
        val base = "MediaBrowser Client=\"Yuri Player\", Device=\"Desktop\", DeviceId=\"$deviceId\", Version=\"1.0\""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    @Serializable
    private data class AuthRequest(
        @SerialName("Username") val username: String,
        @SerialName("Pw") val password: String
    )

    @Serializable
    private data class AuthResponse(
        @SerialName("AccessToken") val accessToken: String? = null,
        @SerialName("User") val user: AuthUser? = null
    )

    @Serializable
    private data class AuthUser(
        @SerialName("Id") val id: String? = null,
        @SerialName("Name") val name: String? = null
    )

    @Serializable
    private data class ItemsResponse(
        @SerialName("Items") val items: List<JfItem> = emptyList(),
        @SerialName("TotalRecordCount") val total: Int? = null
    )

    @Serializable
    private data class JfItem(
        @SerialName("Id") val id: String? = null,
        @SerialName("Name") val name: String? = null,
        @SerialName("Album") val album: String? = null,
        @SerialName("AlbumArtist") val albumArtist: String? = null,
        @SerialName("Artists") val artists: List<String> = emptyList(),
        @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
        @SerialName("IndexNumber") val indexNumber: Int? = null,
        @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
        @SerialName("ProductionYear") val year: Int? = null,
        @SerialName("AlbumId") val albumId: String? = null,
        @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
        @SerialName("BackdropImageTags") val backdropTags: List<String>? = null,
        @SerialName("Container") val container: String? = null
    )
}
