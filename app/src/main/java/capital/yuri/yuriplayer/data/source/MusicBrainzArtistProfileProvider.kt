package capital.yuri.yuriplayer.data.source

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.artistKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fetches artist display name + image via MusicBrainz (artist search + url-rels).
 * Prefers Wikimedia Commons / image relations when present; otherwise no image.
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
            if (dest.exists() || mb.downloadUrl(imageUrl, dest)) {
                imagePath = dest.absolutePath
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
