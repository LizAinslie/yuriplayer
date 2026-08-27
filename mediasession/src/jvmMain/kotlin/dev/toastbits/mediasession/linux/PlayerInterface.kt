package dev.toastbits.mediasession

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.messages.DBusSignal

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@DBusProperty(name = "Metadata", type = Map::class, access = Access.READ)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "LoopStatus", type = String::class)
@DBusProperty(name = "Volume", type = Double::class)
@DBusProperty(name = "Shuffle", type = Boolean::class)
@DBusProperty(name = "Position", type = Long::class, access = Access.READ)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "MinimumRate", type = Double::class)
@DBusProperty(name = "MaximumRate", type = Double::class)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
internal interface PlayerInterface: DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(by_us: Long)
    fun SetPosition(arg0: DBusPath, to_us: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, position_ms: Long): DBusSignal(path, position_ms * 1000)
}
