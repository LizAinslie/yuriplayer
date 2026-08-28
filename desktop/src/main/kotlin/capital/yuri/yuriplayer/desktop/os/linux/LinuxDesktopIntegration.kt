package capital.yuri.yuriplayer.desktop.os.linux

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Registers Yuri as a session application so GNOME/KDE media indicators
 * can resolve [DesktopEntry]=yuriplayer to a name + icon.
 */
object LinuxDesktopIntegration {
    const val APP_ID = "yuriplayer"
    const val DISPLAY_NAME = "Yuri Player"

    fun install() {
        System.setProperty("java.awt.wmclass", APP_ID)
        runCatching { setX11Class() }
        val home = File(System.getProperty("user.home"))
        val apps = File(home, ".local/share/applications").also { it.mkdirs() }
        val icon256 = File(home, ".local/share/icons/hicolor/256x256/apps").also { it.mkdirs() }
        val icon48 = File(home, ".local/share/icons/hicolor/48x48/apps").also { it.mkdirs() }
        val png256 = File(icon256, "$APP_ID.png")
        val png48 = File(icon48, "$APP_ID.png")
        if (!png256.isFile) ImageIO.write(renderIcon(256), "png", png256)
        if (!png48.isFile) ImageIO.write(renderIcon(48), "png", png48)
        File(apps, "$APP_ID.desktop").writeText(desktopFile())
        runCatching {
            ProcessBuilder("update-desktop-database", apps.absolutePath)
                .redirectErrorStream(true).start()
        }
        runCatching {
            ProcessBuilder(
                "gtk-update-icon-cache", "-f", "-t",
                File(home, ".local/share/icons/hicolor").absolutePath
            ).redirectErrorStream(true).start()
        }
    }

    fun windowIcon(): BufferedImage = renderIcon(128)

    private fun desktopFile(): String {
        val exec = ProcessHandle.current().info().command().orElse("yuri-player")
        return """
            [Desktop Entry]
            Type=Application
            Version=1.1
            Name=$DISPLAY_NAME
            GenericName=Music Player
            Comment=Play your library
            Exec=$exec
            Icon=$APP_ID
            Terminal=false
            StartupNotify=true
            StartupWMClass=$APP_ID
            Categories=AudioVideo;Audio;Player;Music;
            MimeType=audio/mpeg;audio/flac;audio/ogg;audio/mp4;audio/x-wav;audio/x-vorbis+ogg;
            Keywords=music;player;library;jellyfin;navidrome;
            DBusActivatable=false
        """.trimIndent() + "\n"
    }

    private fun setX11Class() {
        val toolkit = Class.forName("sun.awt.X11.XToolkit")
        val field = toolkit.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, APP_ID)
    }

    private fun renderIcon(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1A, 0x12, 0x24)
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4)
        g.color = Color(0xC4, 0x58, 0xDC)
        val pad = size / 6
        g.fillOval(pad, pad, size - pad * 2, size - pad * 2)
        g.color = Color(0x1A, 0x12, 0x24)
        val inner = size / 3
        g.fillOval((size - inner) / 2, (size - inner) / 2, inner, inner)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.42f).toInt().coerceAtLeast(10))
        val y = g.fontMetrics
        val text = "Y"
        val tx = (size - y.stringWidth(text)) / 2
        val ty = (size - y.height) / 2 + y.ascent
        g.drawString(text, tx, ty)
        g.dispose()
        return img
    }
}
