package capital.yuri.yuriplayer.components.platform

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Toolkit

actual fun screenSizePx(): IntSize {
    if (GraphicsEnvironment.isHeadless()) return IntSize.Zero
    return try {
        val size = Toolkit.getDefaultToolkit().screenSize
        IntSize(size.width, size.height)
    } catch (_: Throwable) {
        IntSize.Zero
    }
}

actual fun windowPositionOnScreenPx(): IntOffset {
    if (GraphicsEnvironment.isHeadless()) return IntOffset.Zero
    return try {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val window = focus.focusedWindow ?: focus.activeWindow
        window?.let {
            val loc = it.locationOnScreen
            IntOffset(loc.x, loc.y)
        } ?: IntOffset.Zero
    } catch (_: Throwable) {
        IntOffset.Zero
    }
}
