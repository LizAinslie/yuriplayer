package capital.yuri.yuriplayer.data

/**
 * One selectable cover for an album (local tags, folder, remote HTTP, etc.).
 * [uri] is a content/file/http URI used for decode + preference storage.
 */
data class CoverCandidate(
    val id: String,
    val label: String,
    val uri: String,
    /** Optional local song used to pull embedded/folder art. */
    val seedSong: Song? = null,
    val isLocal: Boolean = false
)

/**
 * Platform seam for the cover-picker carousel. The Android implementation walks
 * the filesystem / SAF content URIs ([CoverCandidates.build]); a JVM host can
 * supply its own. Kept behind an interface so [CatalogRepository] stays pure.
 */
fun interface CoverCandidateBuilder {
    fun build(songs: List<Song>, coverPath: String?, coverUrl: String?): List<CoverCandidate>
}
