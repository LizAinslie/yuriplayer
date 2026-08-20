package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.data.source.SourceType
import java.io.File

/**
 * Build a short list of **unique** covers for the picker carousel.
 *
 * Trench-style libraries often embed the same art in every track file — we
 * fingerprint by albumArtUri or parent folder so the carousel shows one local
 * tile, not 14 identical ones. Remote HTTP URIs are unique by URL.
 */
object CoverCandidates {

    fun build(
        songs: List<Song>,
        coverPath: String? = null,
        coverUrl: String? = null
    ): List<CoverCandidate> {
        val out = LinkedHashMap<String, CoverCandidate>()

        fun add(c: CoverCandidate) {
            // Dedupe key = stable fingerprint of the *image*, not the song
            out.putIfAbsent(c.id, c)
        }

        // Local embedded / folder — one per unique art uri or parent dir
        songs.filter { CatalogRepository.sourceTypeForSong(it) == SourceType.LOCAL }.forEach { song ->
            val path = song.path
            val parent = path?.takeIf { !it.contains("://") }?.let { File(it).parent }
            val artUri = song.albumArtUri?.toString()?.takeIf { it.isNotBlank() }

            when {
                artUri != null -> {
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
                parent != null -> {
                    add(
                        CoverCandidate(
                            id = "embedded-folder:$parent",
                            label = "Local file",
                            uri = "file://$path",
                            seedSong = song,
                            isLocal = true
                        )
                    )
                }
            }

            if (parent != null) {
                for (name in FOLDER_COVERS) {
                    val f = File(parent, name)
                    if (f.isFile && f.length() > 0) {
                        add(
                            CoverCandidate(
                                id = "folder:${f.absolutePath}:${f.length()}",
                                label = "Folder cover",
                                uri = "file://${f.absolutePath}",
                                seedSong = song,
                                isLocal = true
                            )
                        )
                        break
                    }
                }
            }
        }

        coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            val uri = if (path.startsWith("file:")) path
            else if (path.startsWith("/")) "file://$path"
            else path
            add(
                CoverCandidate(
                    id = "enriched:$path",
                    label = "Saved cover",
                    uri = uri,
                    isLocal = true
                )
            )
        }

        songs.forEach { song ->
            val uri = song.albumArtUri?.toString() ?: return@forEach
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

    private val FOLDER_COVERS = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.png", "AlbumArt.jpg", "AlbumArt.png"
    )
}
