package capital.yuri.yuriplayer.player

import android.util.Log
import capital.yuri.yuriplayer.data.LibraryIndex
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.data.Song

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
    }

    /**
     * @return true if a new album was selected (caller should [QueueManager.playSource]
     * and rebuffer).
     */
    fun maybePick(
        seedSong: Song?,
        finishedSource: ColdSource?
    ): ArtistRadio.Pick? {
        if (!settings.isAutoPlayRecommendedEnabled()) return null
        // Only when the user is not in any repeat mode
        val exclude = buildSet {
            lastAlbumKey?.let { add(it) }
            priorAlbumKey?.let { add(it) }
            finishedSource?.takeIf { it.type == ColdSourceType.ALBUM }?.id?.let { add(it) }
            seedSong?.let {
                val k = capital.yuri.yuriplayer.data.albumKey(it.album, it.effectiveAlbumArtist)
                if (k.isNotBlank()) add(k)
            }
        }
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
