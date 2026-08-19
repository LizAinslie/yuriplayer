package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.data.db.ArtistProfileEntity
import capital.yuri.yuriplayer.data.db.SourceOverrideDao
import capital.yuri.yuriplayer.data.db.SourceOverrideEntity

enum class SourceType(val rank: Int) {
    LOCAL(0),
    JELLYFIN(10),
    /** OpenSubsonic / Subsonic protocol (Navidrome, Gonic, Official, …). */
    SUBSONIC(20),
    /** @deprecated Prefer [SUBSONIC]; kept for existing source_instances rows. */
    NAVIDROME(20),
    WEBDAV(30),
    OTHER(100);

    companion object {
        fun from(raw: String?): SourceType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER

        /** True for Subsonic-protocol backends including legacy NAVIDROME rows. */
        fun isSubsonicFamily(type: SourceType): Boolean =
            type == SUBSONIC || type == NAVIDROME
    }
}

data class SourceOffering(
    val sourceType: SourceType,
    val sourceId: Long? = null,
    val sourceName: String,
    val song: Song
)

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

class LocalMusicSourceProvider(
    private val songs: () -> List<Song>
) : MusicSourceProvider {
    override val type: SourceType = SourceType.LOCAL
    override val name: String = "Local files"
    override suspend fun listSongs(): List<Song> = songs()
}

enum class LinkCategory {
    OFFICIAL,
    SOCIAL,
    STREAMING,
    DATABASE,
    OTHER
}

data class ArtistLink(
    val label: String,
    val url: String,
    val category: LinkCategory = LinkCategory.OTHER
)

data class ArtistProfile(
    val artistKey: String,
    val displayName: String,
    val bio: String? = null,
    val imageUri: String? = null,
    val websiteUrl: String? = null,
    val links: List<ArtistLink> = emptyList(),
    val genres: List<String> = emptyList(),
    val source: String = "local"
)

/** Upcoming / scheduled live appearance. */
data class ArtistEvent(
    val id: String,
    val title: String,
    val venue: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val datetime: String? = null,
    val url: String? = null,
    val source: String = "bandsintown"
)

interface ArtistProfileProvider {
    val id: String
    suspend fun fetch(artistName: String): ArtistProfile?
}

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

fun ArtistProfileEntity.toProfile(
    links: List<ArtistLink> = emptyList(),
    genres: List<String> = emptyList()
): ArtistProfile =
    ArtistProfile(
        artistKey = artistKey,
        displayName = displayName,
        bio = bio,
        imageUri = imageUri,
        websiteUrl = websiteUrl,
        links = links,
        genres = genres.ifEmpty {
            genresJson?.let { parseGenresJson(it) }.orEmpty()
        },
        source = source
    )

fun parseGenresJson(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())
}

fun genresToJson(genres: List<String>): String? {
    if (genres.isEmpty()) return null
    val arr = org.json.JSONArray()
    genres.distinctBy { it.lowercase() }.forEach { arr.put(it) }
    return arr.toString()
}

/** Infer platform category + friendly label from a URL. */
fun categorizeLink(url: String, fallbackLabel: String? = null): ArtistLink {
    val u = url.lowercase()
    val (label, cat) = when {
        u.contains("bandcamp.com") -> "Bandcamp" to LinkCategory.STREAMING
        u.contains("soundcloud.com") -> "SoundCloud" to LinkCategory.STREAMING
        u.contains("open.spotify.com") || u.contains("spotify.com") ->
            "Spotify" to LinkCategory.STREAMING
        u.contains("music.apple.com") || u.contains("itunes.apple.com") ->
            "Apple Music" to LinkCategory.STREAMING
        u.contains("deezer.com") -> "Deezer" to LinkCategory.STREAMING
        u.contains("tidal.com") -> "Tidal" to LinkCategory.STREAMING
        u.contains("youtube.com") || u.contains("youtu.be") ->
            "YouTube" to LinkCategory.STREAMING
        u.contains("musicbrainz.org") -> "MusicBrainz" to LinkCategory.DATABASE
        u.contains("discogs.com") -> "Discogs" to LinkCategory.DATABASE
        u.contains("wikidata.org") -> "Wikidata" to LinkCategory.DATABASE
        u.contains("wikipedia.org") -> "Wikipedia" to LinkCategory.DATABASE
        u.contains("last.fm") || u.contains("lastfm") -> "Last.fm" to LinkCategory.DATABASE
        u.contains("allmusic.com") -> "AllMusic" to LinkCategory.DATABASE
        u.contains("rateyourmusic.com") || u.contains("rymc.") ->
            "Rate Your Music" to LinkCategory.DATABASE
        u.contains("facebook.com") -> "Facebook" to LinkCategory.SOCIAL
        u.contains("instagram.com") -> "Instagram" to LinkCategory.SOCIAL
        u.contains("twitter.com") || u.contains("x.com/") -> "X / Twitter" to LinkCategory.SOCIAL
        u.contains("tiktok.com") -> "TikTok" to LinkCategory.SOCIAL
        u.contains("bandsintown.com") -> "Bandsintown" to LinkCategory.OTHER
        u.contains("songkick.com") -> "Songkick" to LinkCategory.OTHER
        else -> (fallbackLabel ?: "Link") to LinkCategory.OTHER
    }
    val finalLabel = when {
        fallbackLabel.equals("Website", true) ||
            fallbackLabel.equals("official homepage", true) -> "Website"
        else -> label
    }
    val finalCat =
        if (finalLabel == "Website") LinkCategory.OFFICIAL else cat
    return ArtistLink(finalLabel, url, finalCat)
}
