package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * Thin YuriPlayer wrapper around the official [Jellyfin] Kotlin SDK.
 * Prefer SDK APIs for anything new; only add hand-rolled calls if the SDK
 * cannot do it, then upstream a patch when practical.
 */
class JellyfinClient(
    private val jellyfin: Jellyfin
) {
    data class Session(
        val baseUrl: String,
        val userId: String,
        val accessToken: String,
        val serverName: String? = null,
        /** Live SDK client already holding the access token. */
        val api: ApiClient
    )

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String
    ): Result<Session> = runCatching {
        val root = SourceInstanceRepository.normalizeBaseUrl(baseUrl)
        val user = username.trim()
        require(user.isNotEmpty()) { "Username is empty" }

        Log.i(TAG, "authenticate → $root user=$user (official SDK)")

        val api = jellyfin.createApi(baseUrl = root)
        try {
            val authenticationResult by api.userApi.authenticateUserByName(
                username = user,
                password = password
            )
            val token = authenticationResult.accessToken
                ?: error("No accessToken in authentication result")
            val userId = authenticationResult.user?.id?.toString()
                ?: error("No user id in authentication result")

            api.update(accessToken = token)

            Log.i(
                TAG,
                "auth ok server=${authenticationResult.serverId} userId=${userId.take(8)}…"
            )
            Session(
                baseUrl = root,
                userId = userId,
                accessToken = token,
                serverName = null,
                api = api
            )
        } catch (e: InvalidStatusException) {
            if (e.status == 401) {
                error("Invalid username or password (HTTP 401)")
            }
            throw e
        } catch (e: ApiClientException) {
            error(e.message ?: "Jellyfin API error")
        }
    }.onFailure { Log.w(TAG, "authenticate failed: ${it.message}") }

    /**
     * Lists audio items the user can play (up to [limit]).
     */
    suspend fun listAudioItems(session: Session, limit: Int = 10_000): Result<List<Song>> =
        runCatching {
            val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            val result by session.api.itemsApi.getItems(
                userId = userId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                fields = listOf(
                    ItemFields.PATH,
                    ItemFields.MEDIA_SOURCES,
                    ItemFields.PRIMARY_IMAGE_ASPECT_RATIO
                ),
                sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.INDEX_NUMBER),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = limit
            )
            result.items.orEmpty().mapNotNull { it.toSong(session) }
        }.onFailure { Log.w(TAG, "listAudioItems failed: ${it.message}") }

    fun streamUrl(session: Session, itemId: String): String =
        "${session.baseUrl.trimEnd('/')}/Audio/$itemId/stream?static=true&api_key=${session.accessToken}"

    fun primaryImageUrl(session: Session, itemId: String, maxWidth: Int = 512): String =
        "${session.baseUrl.trimEnd('/')}/Items/$itemId/Images/Primary?maxWidth=$maxWidth&api_key=${session.accessToken}"

    private fun BaseItemDto.toSong(session: Session): Song? {
        val id = id?.toString() ?: return null
        val stream = streamUrl(session, id)
        val art = if (imageTags?.containsKey(
                org.jellyfin.sdk.model.api.ImageType.PRIMARY
            ) == true
        ) {
            Uri.parse(primaryImageUrl(session, id))
        } else null

        // RunTimeTicks is 100-ns units → ms
        val durationMs = runTimeTicks?.let { it / 10_000L }?.takeIf { it > 0 }
        val artists = artists?.filter { it.isNotBlank() }.orEmpty()
        val albumArtists = albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtists?.mapNotNull { it.name }?.firstOrNull()?.takeIf { it.isNotBlank() }

        return Song(
            id = id.hashCode().toLong(),
            title = name,
            artist = artists.firstOrNull() ?: albumArtists,
            albumArtist = albumArtists,
            album = album,
            durationMs = durationMs,
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = indexNumber,
            discNumber = parentIndexNumber,
            year = productionYear,
            path = path,
            mimeType = null
        )
    }

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
