package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.source.SourceType

/**
 * Pure, platform-agnostic cover-candidate assembly (no `java.io.File` /
 * `android.net.Uri`). The Android client injects a fuller builder via
 * [CoverCandidateBuilder] that additionally probes the local filesystem / SAF
 * content URIs for embedded and folder art.
 */
object PureCoverCandidates {

    fun build(
        songs: List<Song>,
        coverPath: String? = null,
        coverUrl: String? = null
    ): List<CoverCandidate> {
        val out = LinkedHashMap<String, CoverCandidate>()

        fun add(c: CoverCandidate) {
            out.putIfAbsent(c.id, c)
        }

        // Local tag art carried directly on the Song (already a URI string).
        songs.filter { CatalogRepository.sourceTypeForSong(it) == SourceType.LOCAL }.forEach { song ->
            val artUri = song.albumArtUri?.takeIf { it.isNotBlank() }
            if (artUri != null) {
                add(
                    CoverCandidate(
                        id = "art:$artUri",
                        label = "Local tag",
                        uri = artUri,
                        seedSong = song,
                        isLocal = true
                    )
                )
            }
        }

        coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            add(
                CoverCandidate(
                    id = "enriched:$path",
                    label = "Saved cover",
                    uri = path,
                    isLocal = true
                )
            )
        }

        songs.forEach { song ->
            val uri = song.albumArtUri ?: return@forEach
            if (!uri.startsWith("http", ignoreCase = true)) return@forEach
            val type = CatalogRepository.sourceTypeForSong(song)
            add(
                CoverCandidate(
                    id = "remote:$uri",
                    label = "${CatalogRepository.friendlySourceName(type.name)} cover",
                    uri = uri,
                    seedSong = song,
                    isLocal = false
                )
            )
        }

        coverUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
            add(
                CoverCandidate(
                    id = "catalog:$url",
                    label = "Catalog cover",
                    uri = url,
                    isLocal = false
                )
            )
        }

        return out.values.sortedBy { if (it.isLocal) 0 else 1 }
    }
}
