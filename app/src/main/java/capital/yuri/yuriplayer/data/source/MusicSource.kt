package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.db.SourceOverrideDao
import capital.yuri.yuriplayer.data.db.SourceOverrideEntity

/** Built-in precedence when no user override is set. Lower = preferred. */
enum class SourceType(val rank: Int) {
    LOCAL(0),
    JELLYFIN(10),
    NAVIDROME(20),
    WEBDAV(30),
    OTHER(100);

    companion object {
        fun from(raw: String?): SourceType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

data class SourceOffering(
    val sourceType: SourceType,
    val sourceId: Long? = null,
    val sourceName: String,
    val song: Song
)

/**
 * A logical track that may exist on multiple backends.
 * [offerings] ordered by resolver preference when returned from [SourceResolver].
 */
data class CatalogTrack(
    val identityKey: String,
    val offerings: List<SourceOffering>
) {
    val preferred: SourceOffering? get() = offerings.firstOrNull()
}

interface MusicSourceProvider {
    val type: SourceType
    val name: String
    suspend fun listSongs(): List<Song>
}

/** Local MediaStore / filesystem scan already owned by LibraryIndex. */
class LocalMusicSourceProvider(
    private val songs: () -> List<Song>
) : MusicSourceProvider {
    override val type: SourceType = SourceType.LOCAL
    override val name: String = "Local files"
    override suspend fun listSongs(): List<Song> = songs()
}

data class ArtistLink(val label: String, val url: String)

data class ArtistProfile(
    val artistKey: String,
    val displayName: String,
    val bio: String? = null,
    val imageUri: String? = null,
    val websiteUrl: String? = null,
    val links: List<ArtistLink> = emptyList(),
    val source: String = "local"
)

interface ArtistProfileProvider {
    val id: String
    suspend fun fetch(artistName: String): ArtistProfile?
}

/** Placeholder until MusicBrainz / Jellyfin artist endpoints land. */
class LocalArtistProfileProvider : ArtistProfileProvider {
    override val id: String = "local"
    override suspend fun fetch(artistName: String): ArtistProfile? {
        val key = artistKey(artistName) ?: return null
        return ArtistProfile(
            artistKey = key,
            displayName = artistName.trim(),
            source = "local"
        )
    }
}

/**
 * Picks which offering to play for a logical track.
 * Order: explicit override → type rank (local first) → first available.
 */
class SourceResolver(
    private val overrideDao: SourceOverrideDao
) {
    suspend fun prefer(
        scope: String,
        scopeKey: String,
        offerings: List<SourceOffering>
    ): SourceOffering? {
        if (offerings.isEmpty()) return null
        val override = overrideDao.get(scope, scopeKey)
        if (override != null) {
            val byId = override.preferredSourceId?.let { id ->
                offerings.firstOrNull { it.sourceId == id }
            }
            if (byId != null) return byId
            val byType = override.preferredSourceType?.let { t ->
                offerings.firstOrNull { it.sourceType == SourceType.from(t) }
            }
            if (byType != null) return byType
        }
        return offerings.minByOrNull { it.sourceType.rank }
    }

    suspend fun setOverride(
        scope: String,
        scopeKey: String,
        sourceId: Long? = null,
        sourceType: String? = null
    ) {
        overrideDao.upsert(
            SourceOverrideEntity(
                scope = scope,
                scopeKey = scopeKey,
                preferredSourceId = sourceId,
                preferredSourceType = sourceType
            )
        )
    }

    suspend fun clearOverride(scope: String, scopeKey: String) {
        overrideDao.delete(scope, scopeKey)
    }
}

fun ArtistProfileEntity.toProfile(links: List<ArtistLink> = emptyList()): ArtistProfile =
    ArtistProfile(
        artistKey = artistKey,
        displayName = displayName,
        bio = bio,
        imageUri = imageUri,
        websiteUrl = websiteUrl,
        links = links,
        source = source
    )
