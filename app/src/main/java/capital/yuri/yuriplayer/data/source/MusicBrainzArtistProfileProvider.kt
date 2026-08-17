package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches artist display name + image via MusicBrainz url-rels,
 * then Wikidata P18 / Wikipedia when MB has no direct image relation.
 */
class MusicBrainzArtistProfileProvider(
    private val context: Context,
    private val mb: MusicBrainzClient
) : ArtistProfileProvider {

    override val id: String = "musicbrainz"

    override suspend fun fetch(artistName: String): ArtistProfile? = withContext(Dispatchers.IO) {
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
            // Re-download if missing or empty (allows fixing after image pipeline upgrade)
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

    companion object {
        private const val TAG = "MBArtist"
    }
}
