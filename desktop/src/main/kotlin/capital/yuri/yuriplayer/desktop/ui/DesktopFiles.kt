package capital.yuri.yuriplayer.desktop.ui

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.io.IOException
import java.net.URI
import javax.swing.SwingUtilities

object DesktopFiles {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun pickImage(title: String = "Choose image"): File? =
        pickImages(title, multiple = false).firstOrNull()

    fun pickImages(title: String = "Choose images", multiple: Boolean = true): List<File> {
        val picked = if (isLinux()) linuxNativePicker(title, multiple) else null
        val files = picked ?: awtPicker(title, multiple)
        return files.filter { it.isFile && it.extension.lowercase() in imageExt }
    }

    fun downloadImage(url: String): File? = runCatching {
        val out = File.createTempFile("yuri-img-", ".img")
        URI(url).toURL().openStream().use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out.takeIf { it.length() > 32 }
    }.getOrNull()

    private fun isLinux(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return os.contains("linux") || os.contains("bsd")
    }

    /**
     * System portal pickers (zenity / kdialog). Returning empty means the user
     * cancelled; null means no native tool was available.
     */
    private fun linuxNativePicker(title: String, multiple: Boolean): List<File>? {
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().lowercase()
        val kdeFirst = "kde" in desktop || "plasma" in desktop
        val tools = if (kdeFirst) listOf(::kdialogCmd, ::zenityCmd) else listOf(::zenityCmd, ::kdialogCmd)
        for (tool in tools) {
            val cmd = tool(title, multiple)
            val result = runPicker(cmd) ?: continue
            return result
        }
        return null
    }

    private fun zenityCmd(title: String, multiple: Boolean): List<String> = buildList {
        add("zenity")
        add("--file-selection")
        add("--title=$title")
        add("--file-filter=Images | *.jpg *.jpeg *.png *.webp *.gif *.bmp *.JPG *.JPEG *.PNG *.WEBP")
        if (multiple) {
            add("--multiple")
            add("--separator=\n")
        }
    }

    private fun kdialogCmd(title: String, multiple: Boolean): List<String> = buildList {
        add("kdialog")
        add("--title")
        add(title)
        if (multiple) add("--multiple")
        add("--getopenfilename")
        add(System.getProperty("user.home") ?: ".")
        add("Images (*.jpg *.jpeg *.png *.webp *.gif *.bmp)")
    }

    private fun runPicker(cmd: List<String>): List<File>? {
        val process = try {
            ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return null
        }
        val text = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code != 0 || text.isEmpty()) return emptyList()
        return text.split('\n', '\u0000')
            .flatMap { line -> if (':' in line && !line.startsWith('/')) line.split(':') else listOf(line) }
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .map { File(it) }
    }

    private fun awtPicker(title: String, multiple: Boolean): List<File> {
        var result: Array<File>? = null
        val task = Runnable {
            val owner = ownerFrame()
            val dialog = if (owner != null) {
                FileDialog(owner, title, FileDialog.LOAD)
            } else {
                FileDialog(null as Frame?, title, FileDialog.LOAD)
            }
            dialog.isMultipleMode = multiple
            dialog.file = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp"
            dialog.modalityType = Dialog.ModalityType.APPLICATION_MODAL
            dialog.isAlwaysOnTop = true
            dialog.isVisible = true
            result = dialog.files
            dialog.dispose()
        }
        if (SwingUtilities.isEventDispatchThread()) task.run()
        else SwingUtilities.invokeAndWait(task)
        return result?.toList().orEmpty()
    }

    private fun ownerFrame(): Frame? {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        fun Window.toFrame(): Frame? = when (this) {
            is Frame -> this
            is Dialog -> owner as? Frame
            else -> null
        }
        focus.activeWindow?.toFrame()?.let { return it }
        focus.focusedWindow?.toFrame()?.let { return it }
        return Frame.getFrames().firstOrNull { it.isShowing }
    }
}
