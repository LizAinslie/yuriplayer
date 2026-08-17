package capital.yuri.yuriplayer.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song
import capital.yuri.yuriplayer.data.albumKey
import capital.yuri.yuriplayer.player.radio.RadioEngine
import capital.yuri.yuriplayer.player.radio.ReleaseClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerController(
    private val context: Context,
    private val historyStore: PlaybackHistoryStore,
    private val radioEngine: RadioEngine,
    private val queueManager: QueueManager
) {

    private var service: MusicService? = null
    private var bound = false
    private var pendingAction: (() -> Unit)? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val historyEntries: StateFlow<List<HistoryEntry>> get() = historyStore.entries

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicService.LocalBinder ?: return
            service = local.getService()
            bound = true
            _isConnected.value = true
            val pending = pendingAction
            pendingAction = null
            try {
                pending?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "pending action failed", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _isConnected.value = false
        }
    }

    fun bind() {
        val intent = Intent(context, MusicService::class.java)
        ContextCompat.startForegroundService(context, intent)
        if (!bound) {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (!bound) return
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        bound = false
        service = null
        _isConnected.value = false
        pendingAction = null
    }

    private fun runOrQueue(action: (MusicService) -> Unit) {
        ensureServiceStarted()
        val s = service
        if (s != null) {
            action(s)
        } else {
            Log.i(TAG, "service not bound yet — queueing action")
            pendingAction = { service?.let(action) }
        }
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        runOrQueue { it.playSource(songs, startIndex, autoPlay = true) }
    }

    fun playSource(
        songs: List<Song>,
        startIndex: Int = 0,
        source: ColdSource? = null
    ) {
        runOrQueue { it.playSource(songs, startIndex, autoPlay = true, source = source) }
    }

    /**
     * Album radio: pool = all credited artists on the album.
     * Plays the seed album first under the radio name, then continues via exhaust.
     * Repeat forced OFF so radio can advance.
     */
    fun startAlbumRadio(album: AlbumItem) {
        val session = radioEngine.startAlbumRadio(album)
        val key = ReleaseClassifier.releaseKey(album)
        queueManager.setRepeatMode(RepeatMode.OFF)
        queueManager.setRadioSession(session)
        runOrQueue {
            it.setRepeatMode(RepeatMode.OFF)
            it.playSource(
                songs = album.songs,
                startIndex = 0,
                autoPlay = true,
                source = ColdSource(
                    type = ColdSourceType.RADIO,
                    id = key,
                    title = session.displayName
                )
            )
            // Re-assert session after playSource (RADIO type keeps it)
            queueManager.setRadioSession(session)
            radioEngine.prefetchUpcoming(
                seedSong = album.songs.firstOrNull(),
                finishedSource = ColdSource(ColdSourceType.RADIO, key, session.displayName),
                repeatMode = RepeatMode.OFF
            )
            queueManager.setRadioUpcoming(radioEngine.upcomingSongs())
        }
    }

    fun startArtistRadio(artistName: String, seedSongs: List<Song> = emptyList()) {
        val session = radioEngine.startArtistRadio(artistName)
        queueManager.setRepeatMode(RepeatMode.OFF)
        queueManager.setRadioSession(session)
        val first = if (seedSongs.isNotEmpty()) {
            seedSongs
        } else {
            // Engine will pick on exhaust; kick off with empty → immediate pick
            emptyList()
        }
        runOrQueue {
            it.setRepeatMode(RepeatMode.OFF)
            if (first.isNotEmpty()) {
                it.playSource(
                    songs = first,
                    startIndex = 0,
                    autoPlay = true,
                    source = ColdSource(
                        type = ColdSourceType.RADIO,
                        id = session.seedId ?: artistName,
                        title = session.displayName
                    )
                )
                queueManager.setRadioSession(session)
                radioEngine.prefetchUpcoming(
                    seedSong = first.firstOrNull(),
                    finishedSource = null,
                    repeatMode = RepeatMode.OFF
                )
                queueManager.setRadioUpcoming(radioEngine.upcomingSongs())
            } else {
                // Force a pick immediately via auto-play path
                queueManager.setRadioSession(session)
                val pick = radioEngine.maybePick(
                    seedSong = null,
                    finishedSource = ColdSource(
                        ColdSourceType.ARTIST,
                        session.seedId ?: artistName,
                        artistName
                    ),
                    repeatMode = RepeatMode.OFF
                )
                if (pick != null) {
                    it.playSource(
                        pick.album.songs,
                        0,
                        autoPlay = true,
                        source = ColdSource(
                            ColdSourceType.RADIO,
                            pick.source.id,
                            session.displayName
                        )
                    )
                    queueManager.setRadioSession(session)
                    queueManager.setRadioUpcoming(radioEngine.upcomingSongs())
                }
            }
        }
    }

    fun stopRadio() {
        radioEngine.stopRadio()
        queueManager.clearRadio()
    }

    fun updateColdFromSource(songs: List<Song>, sourceId: String) {
        runOrQueue { it.updateColdFromSource(songs, sourceId) }
    }

    fun addToHotQueue(song: Song) {
        runOrQueue { it.addToHotQueue(song) }
    }

    fun addToHotQueue(songs: List<Song>) {
        runOrQueue { it.addToHotQueue(songs) }
    }

    fun clearHotQueue() {
        runOrQueue { it.clearHotQueue() }
    }

    fun removeFromHot(index: Int) = service?.removeFromHot(index)
    fun removeFromCold(index: Int) = service?.removeFromCold(index)
    fun moveHot(from: Int, to: Int) = service?.moveHot(from, to)
    fun moveCold(from: Int, to: Int) = service?.moveCold(from, to)
    fun moveColdToHot(index: Int) = service?.moveColdToHot(index)

    fun playQueueItem(lane: QueueLane, index: Int) {
        runOrQueue { it.playQueueItem(lane, index) }
    }

    fun setShuffle(enabled: Boolean) = service?.setShuffle(enabled)
    fun toggleShuffle() {
        val snap = service?.getQueueSnapshot()
        service?.setShuffle(!(snap?.shuffleEnabled ?: false))
    }

    fun cycleRepeatMode() = service?.cycleRepeatMode()
    fun setRepeatMode(mode: RepeatMode) = service?.setRepeatMode(mode)

    fun play() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MusicService::class.java)
        )
        service?.play()
    }

    fun pause() = service?.pause()

    fun togglePlayPause() {
        if (service?.isPlaying() == true) service?.pause()
        else play()
    }

    fun skipToNext() {
        runOrQueue { it.skipToNext() }
    }

    fun skipToPrevious(forceTrackChange: Boolean = false) {
        runOrQueue { it.skipToPrevious(forceTrackChange) }
    }

    fun seekTo(positionMs: Long) = service?.seekTo(positionMs)

    fun seekToFraction(fraction: Float) {
        runOrQueue { it.seekToFraction(fraction) }
    }

    fun peekNext(): Song? = service?.peekNext()
    fun peekPrevious(): Song? = service?.peekPrevious()

    fun clearHistory() {
        historyStore.clear()
        service?.clearHistory()
    }

    fun getHistoryMax(): Int = historyStore.maxEntries
    fun setHistoryMax(n: Int) {
        historyStore.maxEntries = n
        service?.setHistoryMax(n)
    }

    fun isPlayingNow(): Boolean = service?.isPlaying() == true
    fun getCurrentSong(): Song? = service?.getCurrentSong()
    fun getCurrentIndex(): Int = service?.getCurrentIndex() ?: -1
    fun getPositionMs(): Long = service?.getPositionMs() ?: 0L
    fun getDurationMs(): Long = service?.getDurationMs() ?: 0L
    fun getQueue(): List<Song> = service?.getQueue() ?: emptyList()
    fun getQueueSnapshot(): QueueSnapshot =
        service?.getQueueSnapshot() ?: queueManager.getSnapshot()

    fun queueSnapshotFlow(): StateFlow<QueueSnapshot>? = service?.queueSnapshot

    private fun ensureServiceStarted() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MusicService::class.java)
        )
        if (!bound) {
            context.bindService(
                Intent(context, MusicService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    companion object {
        private const val TAG = "YuriPlayer.Ctrl"
    }
}
