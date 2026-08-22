package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.net.Uri
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.http.url
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.instantMixApi
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

        log.i { "authenticate → $root user=$user (official SDK)" }

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

            log.i { "auth ok server=${authenticationResult.serverId} userId=${userId.take(8)}…" }
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
    }.onFailure { log.w { "authenticate failed: ${it.message}" } }

    suspend fun listAudioItems(session: Session, limit: Int = 50_000): Result<List<Song>> =
        runCatching {
            val out = mutableListOf<Song>()
            listAudioItemsPaged(session, pageSize = 500, maxItems = limit) { page, _, _, _ ->
                out += page
            }.getOrThrow()
            out
        }.onFailure { log.w { "listAudioItems failed: ${it.message}" } }

    enum class ListingMode { FULL, LIGHT }

    /** Cheap total for AUDIO items — one tiny request, no library walk. */
    suspend fun audioItemCount(session: Session): Result<Int> = runCatching {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: error("Invalid Jellyfin userId")
        val result by session.api.itemsApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            enableImages = false,
            enableTotalRecordCount = true,
            limit = 1,
            startIndex = 0
        )
        result.totalRecordCount ?: 0
    }.onFailure { log.w { "audioItemCount failed: ${it.message}" } }

    suspend fun listAudioItemsPaged(
        session: Session,
        pageSize: Int = 500,
        maxItems: Int = 50_000,
        startFromIndex: Int = 0,
        mode: ListingMode = ListingMode.FULL,
        sortBy: ItemSortBy = ItemSortBy.SORT_NAME,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        onPage: suspend (songs: List<Song>, startIndex: Int, totalHint: Int?, liveIds: List<String>) -> Unit
    ): Result<Int> = runCatching {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: error("Invalid Jellyfin userId")
        var start = startFromIndex.coerceAtLeast(0)
        var delivered = 0
        var totalHint: Int? = null
        var pageNum = 0
        val fields = if (mode == ListingMode.LIGHT) LIGHT_FIELDS else AUDIO_FIELDS
        val wantImages = true

        if (start > 0) {
            log.i { "resume paging from startIndex=$start mode=$mode" }
        }

        while (delivered < maxItems) {
            val take = minOf(pageSize, maxItems - delivered)
            val result by session.api.itemsApi.getItems(
                userId = userId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                fields = fields,
                enableImages = wantImages,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
                enableTotalRecordCount = true,
                sortBy = listOf(sortBy),
                sortOrder = listOf(sortOrder),
                startIndex = start,
                limit = take
            )
            totalHint = result.totalRecordCount ?: totalHint
            val raw = result.items.orEmpty()
            if (raw.isEmpty()) {
                log.i { "empty page start=$start totalHint=$totalHint page=$pageNum" }
                break
            }

            val page = raw.mapNotNull { item ->
                runCatching { item.toSong(session) }
                    .onFailure { e -> log.w { "toSong failed for '${item.name}': ${e.message}" } }
                    .getOrNull()
            }
            val liveIds = raw.mapNotNull { item ->
                item.id?.toString()?.let { "jellyfin:$it" }
            }
            pageNum++
            log.i { "page#$pageNum start=$start raw=${raw.size} mapped=${page.size} " +
                    "delivered=$delivered totalHint=$totalHint mode=$mode sort=$sortBy" }
            onPage(page, start, totalHint, liveIds)
            delivered += raw.size

            start += raw.size
            if (raw.size < take) break
            if (totalHint != null && start >= totalHint) break
        }

        log.i { "listAudioItemsPaged done delivered=$delivered totalHint=$totalHint pages=$pageNum" }
        delivered
    }.onFailure { log.w { "listAudioItemsPaged failed: ${it.message}" } }

    /**
     * Jellyfin Instant Mix — similar tracks from a seed item (song / album / artist id).
     * Used by radio discovery when [RadioSourcePrefs.useJellyfinInstantMix] is on.
     */
    suspend fun instantMixFromItem(
        session: Session,
        itemId: String,
        limit: Int = 50
    ): Result<List<Song>> = runCatching {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: error("Invalid Jellyfin userId")
        val seedId = runCatching { UUID.fromString(itemId) }.getOrNull()
            ?: error("Invalid Jellyfin itemId")
        val result by session.api.instantMixApi.getInstantMixFromItem(
            itemId = seedId,
            userId = userId,
            limit = limit.coerceIn(1, 200),
            fields = AUDIO_FIELDS,
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY)
        )
        result.items.orEmpty().mapNotNull { it.toSong(session) }
    }.onFailure { log.w { "instantMixFromItem failed: ${it.message}" } }

    data class PlaylistRef(
        val id: String,
        val name: String,
        val songCount: Int,
        val coverUrl: String?,
        val owner: String? = null,
        val owned: Boolean = false
    )

    suspend fun listPlaylists(session: Session): Result<List<PlaylistRef>> = runCatching {
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: error("Invalid Jellyfin userId")
        val result by session.api.itemsApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = 400
        )
        result.items.orEmpty().mapNotNull { item ->
            val id = item.id?.toString() ?: return@mapNotNull null
            val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlaylistRef(
                id = id,
                name = name,
                songCount = item.childCount ?: 0,
                coverUrl = primaryImageUrl(session, id, 256),
                owned = item.canDelete == true
            )
        }
    }.onFailure { log.w { "listPlaylists failed: ${it.message}" } }

    suspend fun playlistSongs(session: Session, playlistId: String): Result<List<Song>> =
        runCatching {
            val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
                ?: error("Invalid Jellyfin userId")
            val parent = runCatching { UUID.fromString(playlistId) }.getOrNull()
                ?: error("Invalid playlist id")
            val result by session.api.itemsApi.getItems(
                userId = userId,
                parentId = parent,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                fields = AUDIO_FIELDS,
                enableImages = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
                limit = 2_000
            )
            result.items.orEmpty().mapNotNull { it.toSong(session) }
        }.onFailure { log.w { "playlistSongs failed: ${it.message}" } }

    suspend fun searchAudio(
        session: Session,
        query: String,
        limit: Int = 40
    ): Result<List<Song>> = searchItems(session, query, BaseItemKind.AUDIO, limit) { it.toSong(session) }

    data class AlbumHit(
        val id: String,
        val name: String,
        val artist: String?,
        val trackCount: Int
    )

    data class ArtistHit(
        val id: String,
        val name: String
    )

    suspend fun searchAlbums(
        session: Session,
        query: String,
        limit: Int = 20
    ): Result<List<AlbumHit>> = searchItems(session, query, BaseItemKind.MUSIC_ALBUM, limit) { item ->
        val id = item.id?.toString() ?: return@searchItems null
        val name = item.name?.takeIf { it.isNotBlank() } ?: return@searchItems null
        AlbumHit(
            id = id,
            name = name,
            artist = item.albumArtist?.takeIf { it.isNotBlank() }
                ?: item.artists?.firstOrNull(),
            trackCount = item.childCount ?: 0
        )
    }

    suspend fun searchMusicArtists(
        session: Session,
        query: String,
        limit: Int = 20
    ): Result<List<ArtistHit>> = searchItems(session, query, BaseItemKind.MUSIC_ARTIST, limit) { item ->
        val id = item.id?.toString() ?: return@searchItems null
        val name = item.name?.takeIf { it.isNotBlank() } ?: return@searchItems null
        ArtistHit(id = id, name = name)
    }

    private suspend fun <T> searchItems(
        session: Session,
        query: String,
        kind: BaseItemKind,
        limit: Int,
        map: (org.jellyfin.sdk.model.api.BaseItemDto) -> T?
    ): Result<List<T>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: error("Invalid Jellyfin userId")
        val result by session.api.itemsApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(kind),
            searchTerm = q,
            fields = if (kind == BaseItemKind.AUDIO) LIGHT_FIELDS else null,
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            enableTotalRecordCount = false,
            limit = limit.coerceIn(1, 100)
        )
        result.items.orEmpty().mapNotNull(map)
    }.onFailure { log.w { "search $kind failed: ${it.message}" } }

    fun streamUrl(session: Session, itemId: String, container: String? = null): String =
        url(session.baseUrl) {
            path("Audio", itemId, "stream")
            param("static", true)
            param("api_key", session.accessToken)
            param("_id", itemId)
            param("Container", containerExt(container))
        }

    fun primaryImageUrl(session: Session, itemId: String, maxWidth: Int = 512): String =
        imageUrl(session, itemId, "Primary", maxWidth)

    fun artistImageUrl(session: Session, artistId: String, maxWidth: Int = 512): String =
        imageUrl(session, artistId, "Primary", maxWidth)

    fun artistBackdropUrl(session: Session, artistId: String, maxWidth: Int = 1920): String =
        imageUrl(session, artistId, "Backdrop", maxWidth)

    private fun imageUrl(
        session: Session,
        itemId: String,
        image: String,
        maxWidth: Int
    ): String = url(session.baseUrl) {
        path("Items", itemId, "Images", image)
        param("maxWidth", maxWidth)
        param("quality", 90)
        param("api_key", session.accessToken)
    }

    private fun BaseItemDto.toSong(session: Session): Song? {
        val id = id?.toString() ?: return null
        val fmt = mediaSources?.firstOrNull()?.container?.takeIf { it.isNotBlank() }
        val stream = streamUrl(session, id, fmt)

        val art = when {
            // Track/single Primary first so Drum Show doesn't inherit Vessel.
            imageTags?.containsKey(ImageType.PRIMARY) == true ->
                Uri.parse(primaryImageUrl(session, id))
            albumId != null && !albumPrimaryImageTag.isNullOrBlank() ->
                Uri.parse(primaryImageUrl(session, albumId.toString()))
            albumId != null ->
                Uri.parse(primaryImageUrl(session, albumId.toString()))
            else -> null
        }

        val durationMs = runTimeTicks?.let { it / 10_000L }?.takeIf { it > 0 }

        val artistNames = buildList {
            artists?.forEach { n -> if (n.isNotBlank()) add(n.trim()) }
            artistItems?.forEach { n -> n.name?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }.distinctBy { it.lowercase() }
        val albumArtistName = albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtists?.mapNotNull { it.name }?.firstOrNull { !it.isNullOrBlank() }
        val extras = artistNames.filter { name ->
            albumArtistName == null || !name.equals(albumArtistName, ignoreCase = true)
        }
        val trackArtistName = when {
            extras.isNotEmpty() -> extras.joinToString("; ")
            artistNames.isNotEmpty() -> artistNames.joinToString("; ")
            else -> albumArtistName
        }

        val rawTitle = name?.takeIf { it.isNotBlank() }
            ?: this.path?.substringAfterLast('/')?.substringBeforeLast('.')
        val title = cleanTrackTitle(rawTitle, indexNumber)

        val genreStr = genres?.filter { it.isNotBlank() }?.joinToString("; ")?.takeIf { it.isNotEmpty() }

        val explicitFlag = officialRating?.contains("explicit", ignoreCase = true) == true ||
            (genreStr?.contains("explicit", ignoreCase = true) == true)

        return Song(
            id = id.hashCode().toLong(),
            title = title,
            artist = trackArtistName ?: albumArtistName,
            albumArtist = albumArtistName ?: trackArtistName,
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            contentUri = Uri.parse(stream),
            albumArtUri = art,
            trackNumber = indexNumber,
            discNumber = parentIndexNumber,
            year = productionYear,
            genre = genreStr,
            path = "jellyfin:$id",
            mimeType = fmt?.let { "audio/$it" },
            explicit = explicitFlag
        )
    }

    companion object {
        private val log = yuriLog("JellyfinClient")
        private const val PREFS = "jellyfin_client"
        private const val KEY_DEVICE_ID = "device_id"

        private val LIGHT_FIELDS = listOf(
            ItemFields.PARENT_ID,
            ItemFields.GENRES
        )

        private val AUDIO_FIELDS = listOf(
            ItemFields.PATH,
            ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.GENRES,
            ItemFields.DATE_CREATED,
            ItemFields.PARENT_ID,
            ItemFields.OVERVIEW,
            ItemFields.TAGS
        )

        private fun containerExt(container: String?): String? {
            val raw = container?.trim()?.lowercase()?.substringBefore(',') ?: return null
            if (raw.isEmpty()) return null
            val c = raw.removePrefix("audio/")
            return when (c) {
                "mpeg", "mp3", "mpga" -> "mp3"
                "x-flac", "flac" -> "flac"
                "mp4", "m4a", "aac", "x-m4a" -> "m4a"
                "ogg", "vorbis", "oga" -> "ogg"
                "opus" -> "opus"
                "wav", "x-wav" -> "wav"
                "aiff", "aif" -> "aiff"
                "wma", "x-ms-wma" -> "wma"
                "ape" -> "ape"
                "wv", "wavpack" -> "wv"
                else -> c.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            }
        }

        /** "01 - Title" / "3. Title" / "12) Title" → Title. Hyphen is first in the class so it is not a range. */
        private val TRACK_NUM_PREFIX = Regex("""^0*(\d{1,3})\s*[-._)]\s+(.+)$""")

        fun cleanTrackTitle(raw: String?, trackNumber: Int?): String? {
            if (raw.isNullOrBlank()) return raw
            val trimmed = raw.trim()
            val prefixed = runCatching { TRACK_NUM_PREFIX.matchEntire(trimmed) }.getOrNull()
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
