package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey

/**
 * Tracks last two cold album keys and, when enabled, starts another same-artist
 * album after the queue ends with Repeat off.
 */
class MusicServiceAutoPlay(
    private val library: LibraryIndex,
    private val settings: LibrarySettings
) {
    private var lastAlbumKey: String? = null
    private var priorAlbumKey: String? = null

    fun noteSource(source: ColdSource?) {
        if (source?.type != ColdSourceType.ALBUM) return
        val id = source.id.takeIf { it.isNotBlank() } ?: return
        if (id.equals(lastAlbumKey, ignoreCase = true)) return
        priorAlbumKey = lastAlbumKey
        lastAlbumKey = id
        Log.d(TAG, "noteSource last=$lastAlbumKey prior=$priorAlbumKey")
    }

    /**
     * @return a pick if auto-play should start a new album; null otherwise.
     */
    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?,
        repeatMode: RepeatMode
    ): ArtistRadio.Pick? {
        if (!settings.isAutoPlayRecommendedEnabled()) {
            Log.d(TAG, "maybePick skip — setting off")
            return null
        }
        if (repeatMode != RepeatMode.OFF) {
            Log.d(TAG, "maybePick skip — repeatMode=$repeatMode (need OFF)")
            return null
        }

        val exclude = buildSet {
            lastAlbumKey?.let { add(it) }
            priorAlbumKey?.let { add(it) }
            finishedSource?.takeIf { it.type == ColdSourceType.ALBUM }?.id?.let { add(it) }
            seedSong?.let {
                val k = albumKey(it.album, it.effectiveAlbumArtist)
                if (k.isNotBlank() && k != "|") add(k)
            }
        }

        Log.i(
            TAG,
            "maybePick seed='${seedSong?.displayTitle}' artist='${seedSong?.effectiveAlbumArtist}' " +
                "source=$finishedSource exclude=$exclude"
        )

        val pick = ArtistRadio.pickNextAlbum(
            library = library,
            seedSong = seedSong,
            finishedSource = finishedSource,
            excludeAlbumKeys = exclude
        )
        if (pick != null) {
            noteSource(pick.source)
            Log.i(TAG, "auto-play recommended → ${pick.album.displayName}")
        }
        return pick
    }

    companion object {
        private const val TAG = "YuriPlayer.AutoPlay"
    }
}
