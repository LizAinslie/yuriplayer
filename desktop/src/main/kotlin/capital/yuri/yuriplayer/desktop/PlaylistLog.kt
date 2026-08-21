package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.library.Track

internal object PlaylistLog {
    fun add(playlist: String, track: Track) {
        log(
            "add '$playlist' title='${track.displayTitle}' id=${track.id} " +
                "source=${track.sourceId} catalog=${track.catalogKey()} loose=${track.looseKey()}"
        )
    }

    fun resolve(
        playlist: String,
        trackIds: List<String>,
        snapshots: List<Track>,
        out: List<Track>,
        missed: List<String>
    ) {
        log(
            "resolve '$playlist' ids=${trackIds.size} snaps=${snapshots.size} " +
                "out=${out.size} missed=$missed titles=${out.map { it.displayTitle }}"
        )
    }

    fun index(msg: String) = log(msg, tag = "YuriPlayer.Index")

    private fun log(msg: String, tag: String = "YuriPlayer.Playlist") {
        System.err.println("$tag $msg")
    }
}
