package capital.yuri.yuriplayer.desktop.os.linux
//
//import capital.yuri.yuriplayer.core.library.Track
//import capital.yuri.yuriplayer.core.log.yuriLog
//import capital.yuri.yuriplayer.core.os.OsMediaControls
//import org.freedesktop.dbus.DBusPath
//import org.freedesktop.dbus.annotations.DBusInterfaceName
//import org.freedesktop.dbus.connections.impl.DBusConnection
//import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
//import org.freedesktop.dbus.exceptions.DBusExecutionException
//import org.freedesktop.dbus.interfaces.DBusInterface
//import org.freedesktop.dbus.interfaces.Introspectable
//import org.freedesktop.dbus.interfaces.Properties
//import org.freedesktop.dbus.messages.DBusSignal
//import org.freedesktop.dbus.types.UInt32
//import org.freedesktop.dbus.types.Variant
//import java.net.URLEncoder
//import java.nio.charset.StandardCharsets
//import java.util.concurrent.atomic.AtomicReference
//import kotlin.math.abs
//
///**
// * org.mpris.MediaPlayer2.yuriplayer on the session bus.
// * GNOME/KDE media keys and `playerctl -p yuriplayer` talk to this.
// */
//class MprisMediaControls : OsMediaControls {
//    private val log = yuriLog("Mpris")
//
//    private val callbacks = AtomicReference<OsMediaControls.Callbacks?>(null)
//    private val state = AtomicReference(NowPlaying())
//    private var connection: DBusConnection? = null
//    private var exported: MprisObject? = null
//    private var gnomeKeys: GnomeMediaKeys? = null
//
//    override fun attach(callbacks: OsMediaControls.Callbacks) {
//        this.callbacks.set(callbacks)
//        try {
//            val conn = DBusConnectionBuilder.forSessionBus()
//                .withShared(false)
//                .build()
//            val obj = MprisObject(callbacks, state)
//            conn.exportObject(OBJECT_PATH, obj)
//            conn.requestBusName(BUS_NAME)
//            connection = conn
//            exported = obj
//            log.w { "MPRIS: claimed $BUS_NAME" }
//            announce(conn, obj)
//            grabGnomeKeys(conn, callbacks)
//        } catch (e: Exception) {
//            log.w(e) { "MPRIS unavailable: ${e.javaClass.simpleName}: ${e.message}" }
//        }
//    }
//
//    private fun announce(conn: DBusConnection, obj: MprisObject) {
//        val now = state.get()
//        runCatching {
//            conn.sendMessage(
//                Properties.PropertiesChanged(
//                    OBJECT_PATH,
//                    ROOT_IFACE,
//                    mapOf(
//                        "Identity" to Variant("Yuri Player"),
//                        "DesktopEntry" to Variant(APP_ID),
//                        "CanRaise" to Variant(true)
//                    ),
//                    emptyList()
//                )
//            )
//            conn.sendMessage(
//                Properties.PropertiesChanged(
//                    OBJECT_PATH,
//                    PLAYER_IFACE,
//                    mapOf(
//                        "PlaybackStatus" to Variant(obj.playbackStatus(now)),
//                        "Metadata" to Variant(obj.metadata(now)),
//                        "CanControl" to Variant(true),
//                        "CanPlay" to Variant(true),
//                        "CanPause" to Variant(true),
//                        "CanGoNext" to Variant(true),
//                        "CanGoPrevious" to Variant(true)
//                    ),
//                    emptyList()
//                )
//            )
//        }
//    }
//
//    private fun grabGnomeKeys(conn: DBusConnection, cb: OsMediaControls.Callbacks) {
//        runCatching {
//            conn.addSigHandler(GnomeMediaKeys.MediaPlayerKeyPressed::class.java) { sig ->
//                if (sig.application != APP_ID) return@addSigHandler
//                when (sig.key) {
//                    "Play", "PlayPause" -> cb.onPlayPause()
//                    "Pause" -> cb.onPause()
//                    "Stop" -> cb.onStop()
//                    "Next" -> cb.onNext()
//                    "Previous" -> cb.onPrevious()
//                }
//            }
//            val keys = conn.getRemoteObject(
//                "org.gnome.SettingsDaemon.MediaKeys",
//                "/org/gnome/SettingsDaemon/MediaKeys",
//                GnomeMediaKeys::class.java
//            )
//            keys.GrabMediaPlayerKeys(APP_ID, UInt32(0))
//            gnomeKeys = keys
//            log.w { "MPRIS: grabbed GNOME media keys" }
//        }.onFailure {
//            log.w { "MPRIS: GNOME media keys skipped (${it.message})" }
//        }
//    }
//
//    override fun update(track: Track?, playing: Boolean, positionMs: Long, durationMs: Long, volume: Float) {
//        val next = NowPlaying(track, playing, positionMs, durationMs, volume.coerceIn(0f, 1f))
//        val prev = state.getAndSet(next)
//        val conn = connection ?: return
//        val obj = exported ?: return
//        val changed = LinkedHashMap<String, Variant<*>>()
//        if (prev.playing != next.playing || (prev.track == null) != (next.track == null)) {
//            changed["PlaybackStatus"] = Variant(obj.playbackStatus(next))
//        }
//        if (prev.track?.id != next.track?.id) {
//            changed["Metadata"] = Variant(obj.metadata(next))
//        }
//        if (abs(prev.volume - next.volume) > 0.01f) {
//            changed["Volume"] = Variant(next.volume.toDouble())
//        }
//        if (changed.isNotEmpty()) {
//            runCatching {
//                conn.sendMessage(
//                    Properties.PropertiesChanged(
//                        OBJECT_PATH,
//                        PLAYER_IFACE,
//                        changed,
//                        emptyList()
//                    )
//                )
//            }
//        }
//        val jump = abs(next.positionMs - prev.positionMs)
//        if (jump > 2_000 && prev.track?.id == next.track?.id && next.track != null) {
//            runCatching {
//                conn.sendMessage(MprisPlayer.Seeked(OBJECT_PATH, next.positionMs * 1000))
//            }
//        }
//    }
//
//    override fun release() {
//        runCatching { gnomeKeys?.ReleaseMediaPlayerKeys(APP_ID) }
//        runCatching { connection?.unExportObject(OBJECT_PATH) }
//        runCatching { connection?.releaseBusName(BUS_NAME) }
//        runCatching { connection?.disconnect() }
//        gnomeKeys = null
//        connection = null
//        exported = null
//    }
//
//    companion object {
//        const val APP_ID = "yuriplayer"
//        const val BUS_NAME = "org.mpris.MediaPlayer2.yuriplayer"
//        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
//        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
//        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
//    }
//}
//
//internal data class NowPlaying(
//    val track: Track? = null,
//    val playing: Boolean = false,
//    val positionMs: Long = 0L,
//    val durationMs: Long = 0L,
//    val volume: Float = 1f
//)
//
//@DBusInterfaceName("org.mpris.MediaPlayer2")
//interface MediaPlayer2 : DBusInterface {
//    fun Raise()
//    fun Quit()
//}
//
//@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
//interface MprisPlayer : DBusInterface {
//    fun Next()
//    fun Previous()
//    fun Pause()
//    fun PlayPause()
//    fun Stop()
//    fun Play()
//    fun Seek(Offset: Long)
//    fun SetPosition(TrackId: DBusPath, Position: Long)
//    fun OpenUri(Uri: String)
//
//    class Seeked(path: String, val Position: Long) : DBusSignal(path, Position)
//}
//
//@DBusInterfaceName("org.gnome.SettingsDaemon.MediaKeys")
//interface GnomeMediaKeys : DBusInterface {
//    fun GrabMediaPlayerKeys(application: String, time: UInt32)
//    fun ReleaseMediaPlayerKeys(application: String)
//
//    class MediaPlayerKeyPressed(
//        path: String,
//        val application: String,
//        val key: String
//    ) : DBusSignal(path, application, key)
//}
//
//internal class MprisObject(
//    private val callbacks: OsMediaControls.Callbacks,
//    private val state: AtomicReference<NowPlaying>
//) : MediaPlayer2, MprisPlayer, Properties, Introspectable {
//
//    override fun isRemote(): Boolean = false
//    override fun getObjectPath(): String = MprisMediaControls.OBJECT_PATH
//
//    override fun Raise() = callbacks.onRaise()
//    override fun Quit() = callbacks.onQuit()
//    override fun Next() = callbacks.onNext()
//    override fun Previous() = callbacks.onPrevious()
//    override fun Pause() = callbacks.onPause()
//    override fun PlayPause() = callbacks.onPlayPause()
//    override fun Stop() = callbacks.onStop()
//    override fun Play() = callbacks.onPlay()
//    override fun Seek(Offset: Long) {
//        val now = state.get()
//        callbacks.onSeek((now.positionMs + Offset / 1000).coerceAtLeast(0))
//    }
//
//    override fun SetPosition(TrackId: DBusPath, Position: Long) {
//        callbacks.onSeek((Position / 1000).coerceAtLeast(0))
//    }
//
//    override fun OpenUri(Uri: String) {}
//
//    override fun <A : Any> Get(interface_name: String, property_name: String): A {
//        val variant = propertiesFor(interface_name, state.get())[property_name]
//            ?: throw DBusExecutionException("Unknown property $property_name")
//        @Suppress("UNCHECKED_CAST")
//        return variant as A
//    }
//
//    override fun GetAll(interface_name: String): Map<String, Variant<*>> =
//        propertiesFor(interface_name, state.get())
//
//    override fun <A : Any> Set(interface_name: String, property_name: String, value: A) {
//        if (property_name != "Volume") return
//        val raw = when (value) {
//            is Variant<*> -> value.value
//            else -> value
//        }
//        val v = (raw as? Number)?.toDouble() ?: return
//        callbacks.onVolume(v.coerceIn(0.0, 1.0).toFloat())
//    }
//
//    override fun Introspect(): String = INTROSPECT_XML
//
//    fun playbackStatus(now: NowPlaying): String = when {
//        now.playing -> "Playing"
//        now.track != null -> "Paused"
//        else -> "Stopped"
//    }
//
//    fun metadata(now: NowPlaying): Map<String, Variant<*>> {
//        val track = now.track ?: return mapOf(
//            "mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack"))
//        )
//        val safe = URLEncoder.encode(track.id.takeLast(48), StandardCharsets.UTF_8)
//            .replace("%", "_")
//            .replace("+", "_")
//            .replace(".", "_")
//        val id = "/org/mpris/MediaPlayer2/Track/$safe"
//        val map = linkedMapOf<String, Variant<*>>(
//            "mpris:trackid" to Variant(DBusPath(id)),
//            "xesam:title" to Variant(track.displayTitle),
//            "xesam:artist" to Variant(track.displayArtist),
//            "xesam:album" to Variant(track.displayAlbum)
//        )
//        if (now.durationMs > 0) {
//            map["mpris:length"] = Variant(now.durationMs * 1000L)
//        }
//        track.artworkUri?.takeIf { it.startsWith("file:") || it.startsWith("http") }?.let {
//            map["mpris:artUrl"] = Variant(it.toString())
//        }
//        return map
//    }
//
//    private fun propertiesFor(iface: String, now: NowPlaying): Map<String, Variant<*>> =
//        when (iface) {
//            MprisMediaControls.ROOT_IFACE -> mapOf(
//                "CanQuit" to Variant(true),
//                "CanRaise" to Variant(true),
//                "HasTrackList" to Variant(false),
//                "Identity" to Variant("Yuri Player"),
//                "DesktopEntry" to Variant("yuriplayer"),
//                "SupportedUriSchemes" to Variant(listOf("file", "http", "https")),
//                "SupportedMimeTypes" to Variant(
//                    listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4")
//                )
//            )
//            MprisMediaControls.PLAYER_IFACE -> playerProps(now)
//            else -> emptyMap()
//        }
//
//    private fun playerProps(now: NowPlaying): Map<String, Variant<*>> = mapOf(
//        "PlaybackStatus" to Variant(playbackStatus(now)),
//        "LoopStatus" to Variant("None"),
//        "Rate" to Variant(1.0),
//        "Shuffle" to Variant(false),
//        "Metadata" to Variant(metadata(now)),
//        "Volume" to Variant(now.volume.toDouble()),
//        "Position" to Variant(now.positionMs * 1000L),
//        "MinimumRate" to Variant(1.0),
//        "MaximumRate" to Variant(1.0),
//        "CanGoNext" to Variant(true),
//        "CanGoPrevious" to Variant(true),
//        "CanPlay" to Variant(true),
//        "CanPause" to Variant(true),
//        "CanSeek" to Variant(true),
//        "CanControl" to Variant(true)
//    )
//
//    companion object {
//        private val INTROSPECT_XML = """
//            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
//            "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
//            <node>
//              <interface name="org.freedesktop.DBus.Introspectable">
//                <method name="Introspect"><arg type="s" direction="out"/></method>
//              </interface>
//              <interface name="org.freedesktop.DBus.Properties">
//                <method name="Get">
//                  <arg type="s" name="interface_name" direction="in"/>
//                  <arg type="s" name="property_name" direction="in"/>
//                  <arg type="v" name="value" direction="out"/>
//                </method>
//                <method name="GetAll">
//                  <arg type="s" name="interface_name" direction="in"/>
//                  <arg type="a{sv}" name="properties" direction="out"/>
//                </method>
//                <method name="Set">
//                  <arg type="s" name="interface_name" direction="in"/>
//                  <arg type="s" name="property_name" direction="in"/>
//                  <arg type="v" name="value" direction="in"/>
//                </method>
//                <signal name="PropertiesChanged">
//                  <arg type="s" name="interface_name"/>
//                  <arg type="a{sv}" name="changed_properties"/>
//                  <arg type="as" name="invalidated_properties"/>
//                </signal>
//              </interface>
//              <interface name="org.mpris.MediaPlayer2">
//                <method name="Raise"/>
//                <method name="Quit"/>
//                <property name="CanQuit" type="b" access="read"/>
//                <property name="CanRaise" type="b" access="read"/>
//                <property name="HasTrackList" type="b" access="read"/>
//                <property name="Identity" type="s" access="read"/>
//                <property name="DesktopEntry" type="s" access="read"/>
//                <property name="SupportedUriSchemes" type="as" access="read"/>
//                <property name="SupportedMimeTypes" type="as" access="read"/>
//              </interface>
//              <interface name="org.mpris.MediaPlayer2.Player">
//                <method name="Next"/>
//                <method name="Previous"/>
//                <method name="Pause"/>
//                <method name="PlayPause"/>
//                <method name="Stop"/>
//                <method name="Play"/>
//                <method name="Seek"><arg type="x" name="Offset" direction="in"/></method>
//                <method name="SetPosition">
//                  <arg type="o" name="TrackId" direction="in"/>
//                  <arg type="x" name="Position" direction="in"/>
//                </method>
//                <method name="OpenUri"><arg type="s" name="Uri" direction="in"/></method>
//                <signal name="Seeked"><arg type="x" name="Position"/></signal>
//                <property name="PlaybackStatus" type="s" access="read"/>
//                <property name="LoopStatus" type="s" access="readwrite"/>
//                <property name="Rate" type="d" access="readwrite"/>
//                <property name="Shuffle" type="b" access="readwrite"/>
//                <property name="Metadata" type="a{sv}" access="read"/>
//                <property name="Volume" type="d" access="readwrite"/>
//                <property name="Position" type="x" access="read"/>
//                <property name="MinimumRate" type="d" access="read"/>
//                <property name="MaximumRate" type="d" access="read"/>
//                <property name="CanGoNext" type="b" access="read"/>
//                <property name="CanGoPrevious" type="b" access="read"/>
//                <property name="CanPlay" type="b" access="read"/>
//                <property name="CanPause" type="b" access="read"/>
//                <property name="CanSeek" type="b" access="read"/>
//                <property name="CanControl" type="b" access="read"/>
//              </interface>
//            </node>
//        """.trimIndent()
//    }
//}
