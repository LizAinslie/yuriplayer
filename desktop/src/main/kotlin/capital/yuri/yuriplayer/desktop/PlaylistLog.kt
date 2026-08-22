package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.log.yuriLog

internal object PlaylistLog {
    private val playlist = yuriLog("Playlist")
    private val index = yuriLog("Index")

    fun add(name: String, track: Track) {
        playlist.d {
            "add '$name' title='${track.displayTitle}' id=${track.id} " +
                "source=${track.sourceId} catalog=${track.catalogKey()} loose=${track.looseKey()}"
        }
    }

    fun resolve(
        playlistName: String,
        trackIds: List<String>,
        snapshots: List<Track>,
        out: List<Track>,
        missed: List<String>
    ) {
        playlist.w {
            "resolve '$playlistName' ids=${trackIds.size} snaps=${snapshots.size} " +
                "out=${out.size} missed=$missed titles=${out.map { it.displayTitle }}"
        }
    }

    fun index(msg: String) = index.d { msg }
}