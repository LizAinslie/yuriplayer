package capital.yuri.yuriplayer.player

import android.net.Uri
import androidx.media3.common.MediaItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.player.engine.resolvePlayableUri
import capital.yuri.yuriplayer.player.engine.toPlaybackMedia
import capital.yuri.yuriplayer.player.engine.urisMatch

/**
 * Shared helpers so [MusicService] resolves local files and remote streams the same way
 * as [capital.yuri.yuriplayer.player.engine.PlaybackEngine].
 */
internal object MusicServicePlaybackHooks {
    fun songUri(song: Song): Uri = resolvePlayableUri(song)

    fun urisEqual(a: Uri?, b: Uri?): Boolean = urisMatch(a, b)

    fun toMediaItem(song: Song, mediaIdSuffix: String? = null): MediaItem {
        val media = song.toPlaybackMedia(mediaIdSuffix)
        return MediaItem.Builder()
            .setUri(media.uri)
            .setMediaId(media.mediaId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(media.title)
                    .setArtist(media.artist)
                    .setAlbumTitle(media.album)
                    .setAlbumArtist(media.albumArtist)
                    .setArtworkUri(media.artworkUri)
                    .build()
            )
            .build()
    }
}
