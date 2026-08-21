package capital.yuri.yuriplayer.core.player

import capital.yuri.yuriplayer.core.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSessionTest {
    private fun tracks(n: Int) = (1..n).map { Track(id = "$it", uri = "file://$it", title = "t$it") }

    @Test
    fun repeatAllWrapsToStart() {
        val engine = FakeEngine()
        val session = PlayerSession(engine)
        session.play(tracks(3), 0)
        session.cycleRepeat() // ALL
        session.skipTo(2)
        session.next()
        assertEquals("1", session.current.value?.id)
    }

    @Test
    fun repeatOffDoesNotWrap() {
        val engine = FakeEngine()
        val session = PlayerSession(engine)
        session.play(tracks(3), 2)
        session.next()
        assertEquals("3", session.current.value?.id)
    }

    @Test
    fun repeatOneRestartsOnEnded() {
        val engine = FakeEngine()
        val session = PlayerSession(engine)
        session.play(tracks(3), 1)
        session.cycleRepeat()
        session.cycleRepeat() // ONE
        engine.emitEnded()
        assertEquals("2", session.current.value?.id)
        assertEquals(0L, engine.lastSeek)
        assertTrue(engine.played)
    }

    @Test
    fun shuffleKeepsCurrentThenRestores() {
        val engine = FakeEngine()
        val session = PlayerSession(engine)
        val list = tracks(8)
        session.play(list, 3)
        assertEquals("4", session.current.value?.id)
        session.toggleShuffle()
        assertTrue(session.shuffle.value)
        assertEquals("4", session.current.value?.id)
        assertEquals("4", session.queue.value.first().id)
        session.toggleShuffle()
        assertFalse(session.shuffle.value)
        assertEquals("4", session.current.value?.id)
        assertEquals(list.map { it.id }, session.queue.value.map { it.id })
    }

    @Test
    fun enqueueAppendsWithoutChangingCurrent() {
        val engine = FakeEngine()
        val session = PlayerSession(engine)
        session.play(tracks(2), 0)
        session.enqueue(Track(id = "9", uri = "file://9", title = "t9"))
        assertEquals("1", session.current.value?.id)
        assertEquals(listOf("1", "2", "9"), session.queue.value.map { it.id })
    }
}

private class FakeEngine : PlaybackEngine {
    private val _playing = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _playing
    private val _uri = MutableStateFlow<String?>(null)
    override val currentUri: StateFlow<String?> = _uri
    private val listeners = mutableListOf<PlaybackEngine.Listener>()
    var lastSeek = -1L
    var played = false
    private var next: PlaybackMedia? = null

    override fun load(current: PlaybackMedia, successor: PlaybackMedia?, startPositionMs: Long) {
        _uri.value = current.uri
        next = successor
        _playing.value = true
    }

    override fun play() {
        played = true
        _playing.value = true
    }

    override fun pause() {
        _playing.value = false
    }

    override fun stop() {
        _playing.value = false
    }

    override fun seekTo(positionMs: Long) {
        lastSeek = positionMs
    }

    override fun getPositionMs(): Long = 0
    override fun getDurationMs(): Long = 180_000
    override fun hasPreparedNext(): Boolean = next != null
    override fun playPreparedNext(): Boolean = false
    override fun setNext(item: PlaybackMedia?) {
        next = item
    }

    override fun release() {}
    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    fun emitEnded() = listeners.forEach { it.onEnded() }
}
