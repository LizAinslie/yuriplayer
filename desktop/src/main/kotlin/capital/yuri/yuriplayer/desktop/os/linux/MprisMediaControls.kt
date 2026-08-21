package capital.yuri.yuriplayer.desktop.os.linux

import capital.yuri.yuriplayer.core.library.Track
import capital.yuri.yuriplayer.core.os.OsMediaControls
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * org.mpris.MediaPlayer2 so `playerctl -p yuriplayer` can pause / skip / read
 * metadata. Session bus only.
 */
class MprisMediaControls : OsMediaControls {
    private val callbacks = AtomicReference<OsMediaControls.Callbacks?>(null)
    private val state = AtomicReference(NowPlaying())
    private var connection: DBusConnection? = null
    private var exported: MprisObject? = null

    override fun attach(callbacks: OsMediaControls.Callbacks) {
        this.callbacks.set(callbacks)
        try {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            conn.requestBusName(BUS_NAME)
            val obj = MprisObject(callbacks, state)
            conn.exportObject(OBJECT_PATH, obj)
            connection = conn
            exported = obj
        } catch (e: Exception) {
            System.err.println("MPRIS unavailable: ${e.message}")
        }
    }

    override fun update(track: Track?, playing: Boolean, positionMs: Long, durationMs: Long, volume: Float) {
        val next = NowPlaying(track, playing, positionMs, durationMs, volume.coerceIn(0f, 1f))
        val prev = state.getAndSet(next)
        val conn = connection ?: return
        if (prev.playing != next.playing ||
            prev.track?.id != next.track?.id ||
            kotlin.math.abs(prev.volume - next.volume) > 0.01f
        ) {
            runCatching {
                conn.sendMessage(
                    Properties.PropertiesChanged(
                        OBJECT_PATH,
                        PLAYER_IFACE,
                        exported?.properties(next) ?: emptyMap(),
                        emptyList()
                    )
                )
            }
        }
    }

    override fun release() {
        runCatching { connection?.unExportObject(OBJECT_PATH) }
        runCatching { connection?.releaseBusName(BUS_NAME) }
        runCatching { connection?.disconnect() }
        connection = null
        exported = null
    }

    companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.yuriplayer"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
    }
}

internal data class NowPlaying(
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f
)

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

internal class MprisObject(
    private val callbacks: OsMediaControls.Callbacks,
    private val state: AtomicReference<NowPlaying>
) : MediaPlayer2, MprisPlayer, Properties, Introspectable {

    override fun getObjectPath(): String = MprisMediaControls.OBJECT_PATH

    override fun Raise() = callbacks.onRaise()
    override fun Quit() = callbacks.onQuit()
    override fun Next() = callbacks.onNext()
    override fun Previous() = callbacks.onPrevious()
    override fun Pause() = callbacks.onPause()
    override fun PlayPause() = callbacks.onPlayPause()
    override fun Stop() = callbacks.onStop()
    override fun Play() = callbacks.onPlay()
    override fun Seek(offset: Long) {
        val now = state.get()
        callbacks.onSeek((now.positionMs + offset / 1000).coerceAtLeast(0))
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        callbacks.onSeek((position / 1000).coerceAtLeast(0))
    }

    override fun OpenUri(uri: String) {}

    override fun <A : Any> Get(interface_name: String, property_name: String): A {
        val variant = propertiesFor(interface_name, state.get())[property_name]
        @Suppress("UNCHECKED_CAST")
        return (variant?.value ?: "") as A
    }

    override fun GetAll(interface_name: String): Map<String, Variant<*>> =
        propertiesFor(interface_name, state.get())

    override fun <A : Any> Set(interface_name: String, property_name: String, value: A) {
        if (property_name != "Volume") return
        val raw = when (value) {
            is Variant<*> -> value.value
            else -> value
        }
        val v = (raw as? Number)?.toDouble() ?: return
        callbacks.onVolume(v.coerceIn(0.0, 1.0).toFloat())
    }

    override fun Introspect(): String = INTROSPECT_XML

    fun properties(now: NowPlaying): Map<String, Variant<*>> =
        propertiesFor(MprisMediaControls.PLAYER_IFACE, now)

    private fun propertiesFor(iface: String, now: NowPlaying): Map<String, Variant<*>> =
        when (iface) {
            MprisMediaControls.ROOT_IFACE -> mapOf(
                "CanQuit" to Variant(true),
                "CanRaise" to Variant(true),
                "HasTrackList" to Variant(false),
                "Identity" to Variant("Yuri Player"),
                "DesktopEntry" to Variant("yuriplayer"),
                "SupportedUriSchemes" to Variant(listOf("file", "http", "https")),
                "SupportedMimeTypes" to Variant(
                    listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4")
                )
            )
            MprisMediaControls.PLAYER_IFACE -> playerProps(now)
            else -> emptyMap()
        }

    private fun playerProps(now: NowPlaying): Map<String, Variant<*>> {
        val status = when {
            now.playing -> "Playing"
            now.track != null -> "Paused"
            else -> "Stopped"
        }
        return mapOf(
            "PlaybackStatus" to Variant(status),
            "LoopStatus" to Variant("None"),
            "Rate" to Variant(1.0),
            "Shuffle" to Variant(false),
            "Metadata" to Variant(metadata(now)),
            "Volume" to Variant(now.volume.toDouble()),
            "Position" to Variant(now.positionMs * 1000),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "CanGoNext" to Variant(true),
            "CanGoPrevious" to Variant(true),
            "CanPlay" to Variant(true),
            "CanPause" to Variant(true),
            "CanSeek" to Variant(true),
            "CanControl" to Variant(true)
        )
    }

    private fun metadata(now: NowPlaying): Map<String, Variant<*>> {
        val track = now.track ?: return mapOf(
            "mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack"))
        )
        val id = "/org/mpris/MediaPlayer2/Track/" +
            URLEncoder.encode(track.id.takeLast(48), StandardCharsets.UTF_8)
                .replace("%", "_")
                .replace("+", "_")
        val map = linkedMapOf<String, Variant<*>>(
            "mpris:trackid" to Variant(DBusPath(id)),
            "xesam:title" to Variant(track.displayTitle),
            "xesam:artist" to Variant(listOf(track.displayArtist)),
            "xesam:album" to Variant(track.displayAlbum)
        )
        if (now.durationMs > 0) {
            map["mpris:length"] = Variant(now.durationMs * 1000)
        }
        track.artworkUri?.let { map["mpris:artUrl"] = Variant(it) }
        return map
    }

    companion object {
        private val INTROSPECT_XML = """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
            "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node>
              <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect"><arg type="s" direction="out"/></method>
              </interface>
              <interface name="org.freedesktop.DBus.Properties">
                <method name="Get">
                  <arg type="s" name="interface_name" direction="in"/>
                  <arg type="s" name="property_name" direction="in"/>
                  <arg type="v" name="value" direction="out"/>
                </method>
                <method name="GetAll">
                  <arg type="s" name="interface_name" direction="in"/>
                  <arg type="a{sv}" name="properties" direction="out"/>
                </method>
                <method name="Set">
                  <arg type="s" name="interface_name" direction="in"/>
                  <arg type="s" name="property_name" direction="in"/>
                  <arg type="v" name="value" direction="in"/>
                </method>
              </interface>
              <interface name="org.mpris.MediaPlayer2">
                <method name="Raise"/>
                <method name="Quit"/>
                <property name="CanQuit" type="b" access="read"/>
                <property name="CanRaise" type="b" access="read"/>
                <property name="HasTrackList" type="b" access="read"/>
                <property name="Identity" type="s" access="read"/>
                <property name="DesktopEntry" type="s" access="read"/>
                <property name="SupportedUriSchemes" type="as" access="read"/>
                <property name="SupportedMimeTypes" type="as" access="read"/>
              </interface>
              <interface name="org.mpris.MediaPlayer2.Player">
                <method name="Next"/>
                <method name="Previous"/>
                <method name="Pause"/>
                <method name="PlayPause"/>
                <method name="Stop"/>
                <method name="Play"/>
                <method name="Seek"><arg type="x" name="Offset" direction="in"/></method>
                <method name="SetPosition">
                  <arg type="o" name="TrackId" direction="in"/>
                  <arg type="x" name="Position" direction="in"/>
                </method>
                <method name="OpenUri"><arg type="s" name="Uri" direction="in"/></method>
                <property name="PlaybackStatus" type="s" access="read"/>
                <property name="LoopStatus" type="s" access="readwrite"/>
                <property name="Rate" type="d" access="readwrite"/>
                <property name="Shuffle" type="b" access="readwrite"/>
                <property name="Metadata" type="a{sv}" access="read"/>
                <property name="Volume" type="d" access="readwrite"/>
                <property name="Position" type="x" access="read"/>
                <property name="MinimumRate" type="d" access="read"/>
                <property name="MaximumRate" type="d" access="read"/>
                <property name="CanGoNext" type="b" access="read"/>
                <property name="CanGoPrevious" type="b" access="read"/>
                <property name="CanPlay" type="b" access="read"/>
                <property name="CanPause" type="b" access="read"/>
                <property name="CanSeek" type="b" access="read"/>
                <property name="CanControl" type="b" access="read"/>
              </interface>
            </node>
        """.trimIndent()
    }
}
