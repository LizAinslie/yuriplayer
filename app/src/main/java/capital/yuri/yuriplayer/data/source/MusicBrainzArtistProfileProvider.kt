package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MusicBrainz-backed [ArtistInfoSource] + legacy [ArtistProfileProvider].
 * Images: direct MB image rel → Wikidata P18 → Wikipedia summary.
 */
class MusicBrainzArtistProfileProvider(
    private val context: Context,
    private val mb: MusicBrainzClient
) : ArtistProfileProvider, ArtistInfoSource {

    override val id: String = "musicbrainz"
    override val displayName: String = "MusicBrainz"

    override suspend fun fetch(artistName: String): ArtistProfile? = fetchProfile(artistName)

    override suspend fun fetchProfile(artistName: String): ArtistProfile? = withContext(Dispatchers.IO) {
        val key = artistKey(artistName) ?: return@withContext null
        val hit = mb.searchArtist(artistName) ?: return@withContext ArtistProfile(
            artistKey = key,
            displayName = artistName.trim(),
            source = id
        )

        var imagePath: String? = null
        val imageUrl = hit.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            val dir = File(context.filesDir, "artist_art")
            dir.mkdirs()
            val dest = File(dir, "${key.hashCode()}.jpg")
            val ok = dest.isFile && dest.length() > 0L || mb.downloadUrl(imageUrl, dest)
            if (ok && dest.isFile && dest.length() > 0L) {
                imagePath = dest.absolutePath
            } else {
                Log.w(TAG, "Failed to download artist image for $artistName from $imageUrl")
            }
        }

        ArtistProfile(
            artistKey = key,
            displayName = hit.name.ifBlank { artistName.trim() },
            bio = null,
            imageUri = imagePath?.let { "file://$it" },
            websiteUrl = hit.website,
            links = hit.links,
            source = id
        )
    }

    override suspend fun fetchImageCandidates(
        artistName: String,
        kind: ArtistImageKind
    ): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val hit = mb.searchArtist(artistName) ?: return@withContext emptyList()
        val out = mutableListOf<ArtistImageCandidate>()
        hit.imageUrl?.let {
            out += ArtistImageCandidate(it, id, "MusicBrainz image")
        }
        // Wikidata / Wikipedia URLs themselves aren't images; searchArtist already
        // resolved imageUrl from those. Also surface any remaining link-derived images.
        hit.links.filter {
            it.url.contains("upload.wikimedia.org", true) ||
                it.url.contains("commons.wikimedia.org", true)
        }.forEach {
            out += ArtistImageCandidate(it.url, id, it.label)
        }
        out.distinctBy { it.url }
    }

    companion object {
        private const val TAG = "MBArtist"
    }
}
