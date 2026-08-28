package capital.yuri.yuriplayer.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import capital.yuri.yuriplayer.core.log.yuriLog
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [PlaybackEngine] backed by Media3 ExoPlayer.
 *
 * Supports local files (content:// / file://) and HTTP(S) streams (Jellyfin /
 * Subsonic). [DefaultDataSource.Factory] routes local URIs to the platform
 * sources and network URIs through a shared [DefaultHttpDataSource.Factory]
 * so session headers still apply for remote libraries.
 */
@OptIn(UnstableApi::class)
class Media3PlaybackEngine(
    context: Context
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()
    private val prefetcher = StreamPrefetcher.get(appContext)

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setUserAgent("YuriPlayer/0.1 (Media3)")

    // Local content/file first; HTTP only for network schemes.
    private val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)

    private val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
        .setDataSourceFactory(dataSourceFactory)

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 120_000, 2_000, 5_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val renderersFactory = DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            dispatch { onIsPlayingChanged(isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentUri.value = mediaItem?.localConfiguration?.uri
            val r = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> PlaybackEngine.TransitionReason.AUTO
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> PlaybackEngine.TransitionReason.SEEK
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> PlaybackEngine.TransitionReason.PLAYLIST
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> PlaybackEngine.TransitionReason.REPEAT
                else -> PlaybackEngine.TransitionReason.OTHER
            }
            dispatch { onMediaTransition(r) }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Don't mutate the playlist inside the transition callback.
                mainHandler.post {
                    compactPlayed()
                    listeners.toList().forEach { runCatching { it.onAutoAdvanced() } }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val state = when (playbackState) {
                Player.STATE_IDLE -> PlaybackEngine.PlaybackState.IDLE
                Player.STATE_BUFFERING -> PlaybackEngine.PlaybackState.BUFFERING
                Player.STATE_READY -> PlaybackEngine.PlaybackState.READY
                Player.STATE_ENDED -> PlaybackEngine.PlaybackState.ENDED
                else -> PlaybackEngine.PlaybackState.IDLE
            }
            dispatch { onPlaybackStateChanged(state) }
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                val f = player.audioFormat
                if (f != null) {
                    AudioPipeline.noteDecoded(
                        codec = f.sampleMimeType,
                        sampleRateHz = f.sampleRate.takeIf { it > 0 && it != Format.NO_VALUE },
                        channels = f.channelCount.takeIf { it > 0 && it != Format.NO_VALUE },
                        bitrateBps = f.bitrate.takeIf { it > 0 && it != Format.NO_VALUE }
                    )
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                buffering = false
                val live = isLive()
                if (live) {
                    if (player.playWhenReady) {
                        player.seekTo(0L)
                        player.play()
                    }
                    return
                }
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.playWhenReady = true
                } else {
                    dispatch { onEnded() }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            log.e(error) { "player error code=${error.errorCode} ${error.message}" }
            val recoverable =
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            dispatch { onError(error.message ?: "Playback error", recoverable) }
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        // NETWORK so remote streams keep the radio up
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(loadControl)
        .setPauseAtEndOfMediaItems(false)
        .build()
        .also {
            it.repeatMode = Player.REPEAT_MODE_OFF
            it.addListener(playerListener)
            // Prefetch the next playlist item so skip / auto-advance is immediate.
            it.preloadConfiguration = ExoPlayer.PreloadConfiguration(NEXT_PRELOAD_US)
        }

    /** Exposed for MediaSession on Android. */
    fun exoPlayer(): ExoPlayer = player

    private var buffering: Boolean = false
    private val _isPlaying = MutableStateFlow(false)

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _currentUri = MutableStateFlow<Uri?>(null)

    override val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    override fun setWindow(items: List<PlaybackMedia>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            _currentUri.value = null
            return
        }
        applyHeaders(items)
        items.filter { it.isNetwork && !it.live }.forEach { prefetcher.start(it) }
        val playable = if (items.getOrNull(startIndex)?.live == true) {
            listOf(items[startIndex.coerceIn(0, items.lastIndex)])
        } else {
            items.map { prefetcher.cached(it) }
        }
        val mediaItems = playable.map { it.toMediaItem() }
        val idx = if (playable.size == 1) 0 else startIndex.coerceIn(0, mediaItems.lastIndex)
        player.setMediaItems(mediaItems, idx, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        _currentUri.value = playable.getOrNull(idx)?.uri
        log.i { "setWindow size=${playable.size} start=$idx network=${playable[idx].isNetwork} live=${playable[idx].live}" }
    }

    private fun applyHeaders(items: List<PlaybackMedia>) {
        val headers = items.firstOrNull { it.headers.isNotEmpty() }?.headers.orEmpty()
        httpFactory.setDefaultRequestProperties(headers)
    }

    /** Drop already-played items so current is always index 0 and next is index 1. */
    private fun compactPlayed() {
        val idx = player.currentMediaItemIndex
        if (idx > 0 && player.mediaItemCount > idx) {
            player.removeMediaItems(0, idx)
        }
    }

    override fun play() {
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }

    override fun seekTo(index: Int, positionMs: Long) {
        if (player.mediaItemCount <= 0) return
        val idx = index.coerceIn(0, player.mediaItemCount - 1)
        player.seekTo(idx, positionMs.coerceAtLeast(0L))
    }

    override fun seekToNext() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    override fun prepare() {
        player.prepare()
    }

    override fun getPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun getDurationMs(): Long {
        val d = player.duration
        return if (d > 0L && d != C.TIME_UNSET) d else 0L
    }

    override fun getCurrentIndex(): Int = player.currentMediaItemIndex.coerceAtLeast(0)

    override fun getMediaCount(): Int = player.mediaItemCount

    override fun getUriAt(index: Int): Uri? {
        if (index < 0 || index >= player.mediaItemCount) return null
        return player.getMediaItemAt(index).localConfiguration?.uri
    }

    override fun setPlayWhenReady(value: Boolean) {
        player.playWhenReady = value
    }

    override fun getPlayWhenReady(): Boolean = player.playWhenReady

    override fun isBuffering(): Boolean = buffering

    override fun isLive(): Boolean {
        val idx = player.currentMediaItemIndex
        if (idx < 0 || idx >= player.mediaItemCount) return false
        return player.getMediaItemAt(idx).mediaId.endsWith("-live")
    }

    override fun setNext(item: PlaybackMedia?) {
        if (isLive()) return
        if (player.mediaItemCount <= 0) {
            if (item != null) {
                applyHeaders(listOf(item))
                player.setMediaItems(listOf(item.toMediaItem()), 0, 0L)
                player.prepare()
                _currentUri.value = item.uri
            }
            return
        }
        compactPlayed()
        val queuedId = if (player.mediaItemCount > 1) {
            player.getMediaItemAt(1).mediaId
        } else {
            null
        }
        if (item == null) {
            while (player.mediaItemCount > 1) {
                player.removeMediaItem(player.mediaItemCount - 1)
            }
            return
        }
        if (item.isNetwork) prefetcher.start(item)
        val playable = prefetcher.cached(item)
        // Same upcoming item is already in the window — keep its buffer.
        if (queuedId == playable.mediaId && player.mediaItemCount == 2) return
        while (player.mediaItemCount > 1) {
            player.removeMediaItem(player.mediaItemCount - 1)
        }
        applyHeaders(listOf(playable))
        player.addMediaItem(playable.toMediaItem())
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        log.i { "setNext '${playable.title}' network=${playable.isNetwork}" }
    }

    override fun hasPreparedNext(): Boolean = player.hasNextMediaItem()

    override fun preparedNextId(): String? {
        if (!player.hasNextMediaItem()) return null
        val idx = player.currentMediaItemIndex + 1
        if (idx < 0 || idx >= player.mediaItemCount) return null
        return player.getMediaItemAt(idx).mediaId
    }

    override fun playPreparedNext(): Boolean {
        if (!player.hasNextMediaItem()) return false
        player.seekToNextMediaItem()
        player.playWhenReady = true
        mainHandler.post { compactPlayed() }
        return true
    }

    override fun release() {
        listeners.clear()
        player.release()
    }

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun PlaybackMedia.toMediaItem(): MediaItem {
        val b = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(mediaId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
        if (live) {
            b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        }
        return b.build()
    }

    private inline fun dispatch(crossinline block: PlaybackEngine.Listener.() -> Unit) {
        val copy = listeners.toList()
        mainHandler.post {
            copy.forEach { runCatching { it.block() } }
        }
    }

    companion object {
        private val log = yuriLog("Media3Engine")
        private const val NEXT_PRELOAD_US = 30_000_000L

        val DESCRIPTOR = PlaybackEngineDescriptor(
            id = "media3",
            displayName = "Media3 (ExoPlayer)",
            description = "Android Media3 backend — local files and HTTP streams",
            platforms = setOf("android")
        )
    }
}
