package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.net.Uri
import android.util.Log
import capital.yuri.yuriplayer.data.Song
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * Thin YuriPlayer wrapper around the official [Jellyfin] Kotlin SDK.
 */
class JellyfinClient(
    private val jellyfin: Jellyfin
) {
    data class Session(
        val baseUrl: String,
        val userId: String,
        val accessToken: String,
        val serverName: String? = null,
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

    suspend fun listAudioItems(session: Session, limit: Int = 50_000): Result<List<Song>> =
        runCatching {
            val out = mutableListOf<Song>()
            listAudioItemsPaged(session, pageSize = 500, maxItems = limit) { page, _, _ ->
                out += page
            }.getOrThrow()
            out
        }.onFailure { Log.w(TAG, "listAudioItems failed: ${it.message}") }

    suspend fun listAudioItemsPaged(
        session: Session,
        pageSize: Int = 500,
        maxItems: Int = 50_000,
        onPage: suspend (songs: List<Song>, startIndex: Int, totalHint: Int?) -> Unit
    ): Result<Int> = runCatching {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
        var start = 0
        var delivered = 0
        var totalHint: Int? = null

        while (delivered < maxItems) {
            val take = minOf(pageSize, maxItems - delivered)
            val result by session.api.itemsApi.getItems(
                userId = userId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                fields = AUDIO_FIELDS,
                enableImages = true,
                enableTotalRecordCount = true,
                sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = start,
                limit = take
            )
            totalHint = result.totalRecordCount ?: totalHint
            val raw = result.items.orEmpty()
            if (raw.isEmpty()) {
                Log.i(TAG, "listAudioItemsPaged empty page at start=$start totalHint=$totalHint")
                break
            }

            val page = raw.mapNotNull { it.toSong(session) }
            Log.i(
                TAG,
                "page start=$start raw=${raw.size} mapped=${page.size} " +
                    "delivered=$delivered totalHint=$totalHint"
            )
            if (page.isNotEmpty()) {
                onPage(page, start, totalHint)
                delivered += page.size
            }

            start += raw.size
            if (raw.size < take) break
            if (totalHint != null && start >= totalHint) break
        }

        Log.i(TAG, "listAudioItemsPaged done delivered=$delivered totalHint=$totalHint")
        delivered
    }.onFailure { Log.w(TAG, "listAudioItemsPaged failed: ${it.message}") }

    /**
     * Direct static stream. Path ends with `/{itemId}` so Media3 / MusicService
     * URI equality (which compares lastPathSegment) stays unique per track.
     */
    fun streamUrl(session: Session, itemId: String): String {
        val root = session.baseUrl.trimEnd('/')
        // Include item id in the path so lastPathSegment ≠ "stream" for every track
        return "$root/Audio/$itemId/stream/$itemId" +
            "?static=true&api_key=${session.accessToken}"
    }

    fun primaryImageUrl(session: Session, itemId: String, maxWidth: Int = 512): String =
        "${session.baseUrl.trimEnd('/')}/Items/$itemId/Images/Primary?maxWidth=$maxWidth&api_key=${session.accessToken}"

    private fun BaseItemDto.toSong(session: Session): Song? {
        val id = id?.toString() ?: return null
        val stream = streamUrl(session, id)

        val art = when {
            imageTags?.containsKey(ImageType.PRIMARY) == true ->
                Uri.parse(primaryImageUrl(session, id))
            albumId != null && !albumPrimaryImageTag.isNullOrBlank() ->
                Uri.parse(
                    "${session.baseUrl.trimEnd('/')}/Items/$albumId/Images/Primary" +
                        "?maxWidth=512&api_key=${session.accessToken}"
                )
            else -> null
        }

        val durationMs = runTimeTicks?.let { it / 10_000L }?.takeIf { it > 0 }

        val artistFromList = artists?.firstOrNull { it.isNotBlank() }
            ?: artistItems?.mapNotNull { it.name }?.firstOrNull { !it.isNullOrBlank() }
        val albumArtistName = albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtists?.mapNotNull { it.name }?.firstOrNull { !it.isNullOrBlank() }

        val rawTitle = name?.takeIf { it.isNotBlank() }
            ?: path?.substringAfterLast('/')?.substringBeforeLast('.')
        val title = cleanTrackTitle(rawTitle, indexNumber)

        return Song(
            id = id.hashCode().toLong(),
            title = title,
            artist = artistFromList ?: albumArtistName,
            albumArtist = albumArtistName ?: artistFromList,
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = indexNumber,
            discNumber = parentIndexNumber,
            year = productionYear,
            // Catalog key only — not a filesystem path (MusicService must not File() this)
            path = "jellyfin:$id",
            mimeType = null
        )
    }

    companion object {
        private const val TAG = "JellyfinClient"
        private const val PREFS = "jellyfin_client"
        private const val KEY_DEVICE_ID = "device_id"

        private val AUDIO_FIELDS = listOf(
            ItemFields.PATH,
            ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.GENRES,
            ItemFields.DATE_CREATED,
            ItemFields.PARENT_ID,
            ItemFields.OVERVIEW
        )

        fun cleanTrackTitle(raw: String?, trackNumber: Int?): String? {
            if (raw.isNullOrBlank()) return raw
            val trimmed = raw.trim()
            val prefixed = Regex("""^0*(\d{1,3})\s*[\-._)]\s+(.+)$""").matchEntire(trimmed)
            if (prefixed != null) {
                val num = prefixed.groupValues[1].toIntOrNull()
                val rest = prefixed.groupValues[2].trim()
                if (rest.isNotEmpty() && (trackNumber == null || num == null || num == trackNumber)) {
                    return rest
                }
            }
            return trimmed
        }

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
