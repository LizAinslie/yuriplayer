package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.catalogKey
import capital.yuri.yuriplayer.core.library.looseKey
import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.Song

internal object PlaylistLog {
    private val playlist = yuriLog("Playlist")
    private val index = yuriLog("Index")

    fun add(name: String, track: Song) {
        playlist.d {
            "add '$name' title='${track.displayTitle}' id=${track.songKey} " +
                "source=${track.sourceId} catalog=${track.catalogKey()} loose=${track.looseKey()}"
        }
    }

    fun resolve(
        playlistName: String,
        trackIds: List<String>,
        snapshots: List<Song>,
        out: List<Song>,
        missed: List<String>
    ) {
        playlist.w {
            "resolve '$playlistName' ids=${trackIds.size} snaps=${snapshots.size} " +
                "out=${out.size} missed=$missed titles=${out.map { it.displayTitle }}"
        }
    }

    fun index(msg: String) = index.d { msg }
}
