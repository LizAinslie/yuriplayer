package capital.yuri.yuriplayer.data

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.db.CatalogTrackEntity

/**
 * Album expand / merge diagnostics.
 *
 * Tag is always [TAG] = `YuriAlbum` → logcat `YuriPlayer.YuriAlbum`.
 * Every line starts with `[album title]` so you can grep Clancy / self-titled
 * without knowing the code path.
 *
 * Levels:
 * - V / TRACE — every track, every identity group member
 * - D         — query step counts, grouping
 * - I         — path summaries (seed → expand → merge → page)
 * - W         — collapse / identity collision / never-shrink
 */
object AlbumLog {
    const val TAG = "YuriAlbum"
    private val log = yuriLog(TAG)

    fun i(album: String?, msg: String) = log.i { line(album, msg) }
    fun d(album: String?, msg: String) = log.d { line(album, msg) }
    fun v(album: String?, msg: String) = log.t { line(album, msg) }
    fun w(album: String?, msg: String) = log.w { line(album, msg) }

    fun step(album: String?, name: String, count: Int, extra: String = "") {
        val suffix = if (extra.isNotEmpty()) " $extra" else ""
        log.d { line(album, "step $name n=$count$suffix") }
    }

    fun songs(album: String?, label: String, tracks: List<Song>) {
        log.d { line(album, "$label n=${tracks.size}") }
        tracks.forEachIndexed { i, s ->
            log.t { line(album, "  $label[$i] ${songLine(s)}") }
        }
    }

    fun entities(album: String?, label: String, rows: Collection<CatalogTrackEntity>) {
        log.d { line(album, "$label n=${rows.size}") }
        rows.forEachIndexed { i, r ->
            log.t { line(album, "  $label[$i] ${entityLine(r)}") }
        }
    }

    fun identityGroups(album: String?, groups: Map<String, List<Song>>) {
        log.d { line(album, "identity groups=${groups.size}") }
        groups.forEach { (id, members) ->
            if (members.size > 1) {
                log.w {
                    line(
                        album,
                        "COLLIDE identity=$id n=${members.size} titles=${members.joinToString(" | ") { it.displayTitle }}"
                    )
                }
                members.forEachIndexed { i, s ->
                    log.t { line(album, "    collide[$i] ${songLine(s)}") }
                }
            } else {
                log.t { line(album, "  id=$id → ${members.firstOrNull()?.displayTitle}") }
            }
        }
    }

    fun songLine(s: Song): String {
        val src = CatalogRepository.sourceTypeForSong(s)
        return "title='${s.title}' tn=${s.trackNumber} disc=${s.discNumber} " +
            "album='${s.album}' aa='${s.albumArtist}' ar='${s.artist}' " +
            "src=$src key=${s.songKey} path=${s.path} " +
            "aKey=${albumKey(s.album, s.effectiveAlbumArtist)} id=${albumPageIdentity(s)}"
    }

    fun entityLine(r: CatalogTrackEntity): String =
        "title='${r.title}' tn=${r.trackNumber} disc=${r.discNumber} " +
            "album='${r.album}' aa='${r.albumArtist}' ar='${r.artist}' " +
            "src=${r.sourceType} songKey=${r.songKey} aKey=${r.albumKey} " +
            "artistKey=${r.artistKey} ext=${r.externalId}"

    private fun line(album: String?, msg: String): String {
        val label = album?.trim().orEmpty().ifBlank { "?" }
        return "[$label] $msg"
    }
}
