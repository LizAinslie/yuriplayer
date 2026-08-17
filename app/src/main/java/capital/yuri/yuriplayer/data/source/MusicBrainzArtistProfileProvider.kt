package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MusicBrainz-backed [ArtistInfoSource] + legacy [ArtistProfileProvider].
 * Candidates: MB image rel, every Wikidata P18 via MB links, Wikipedia summary
 * via MB wikipedia rel (other sources also search those catalogs directly).
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
        val out = LinkedHashMap<String, ArtistImageCandidate>()

        hit.imageUrl?.takeIf { it.isNotBlank() }?.let {
            out[it] = ArtistImageCandidate(it, id, "MusicBrainz resolved")
        }

        // Extra candidates resolved from MB url-rels (may overlap other sources — deduped upstream)
        mb.expandImageCandidates(hit).forEach { (url, label) ->
            if (url !in out) out[url] = ArtistImageCandidate(url, id, label)
        }

        out.values.toList()
    }

    companion object {
        private const val TAG = "MBArtist"
    }
}
