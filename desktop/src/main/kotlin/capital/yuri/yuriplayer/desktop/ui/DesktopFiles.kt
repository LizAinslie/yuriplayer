package capital.yuri.yuriplayer.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.SwingUtilities

object DesktopFiles {
    fun pickImage(title: String = "Choose image"): File? {
        val files = pickImages(title, multiple = false)
        return files.firstOrNull()
    }

    fun pickImages(title: String = "Choose images", multiple: Boolean = true): List<File> {
        var result: Array<File>? = null
        val task = Runnable {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isMultipleMode = multiple
            dialog.setFilenameFilter { _, name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
            }
            dialog.isVisible = true
            result = dialog.files
        }
        if (SwingUtilities.isEventDispatchThread()) task.run()
        else SwingUtilities.invokeAndWait(task)
        return result?.toList().orEmpty()
    }

    fun downloadImage(url: String): File? = runCatching {
        val out = File.createTempFile("yuri-img-", ".img")
        URI(url).toURL().openStream().use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out.takeIf { it.length() > 32 }
    }.getOrNull()
}
